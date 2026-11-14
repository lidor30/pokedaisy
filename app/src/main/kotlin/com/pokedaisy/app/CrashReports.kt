package com.pokedaisy.app

import android.content.Context
import android.util.Log
import com.google.firebase.crashlytics.FirebaseCrashlytics
import java.io.File

/**
 * Crash reports through Firebase Crashlytics (native crashes too, via crashlytics-ndk) -
 * **opt-in**: the manifest turns collection off, and only the player's yes (Settings >
 * CRASH REPORTS, or the one-time ask in the Library) turns it on. Turning it off again
 * also drops anything not yet sent.
 *
 * A build without app/google-services.json (forks, PR checks) has no Firebase project:
 * [available] is false, the row and the ask don't show, and every call here is a no-op.
 * ui-preview has its own stand-in (it can't load Firebase).
 */
object CrashReports {
    /** Whether this build can send reports at all. */
    val available: Boolean get() = BuildConfig.CRASHLYTICS

    /** Whether to ask - once, after the third open and two days on from the install. */
    fun shouldAsk(context: Context, prefs: Prefs, now: Long = System.currentTimeMillis()): Boolean {
        if (!available || prefs.crashReportsAsked || prefs.crashReportsEnabled) return false
        val installed = runCatching {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageInfo(context.packageName, 0).firstInstallTime
        }.getOrDefault(now)
        return shouldAsk(prefs.appOpenCount, installed, now)
    }

    /** The rule itself, for tests: [ASK_AFTER_OPENS] opens and [ASK_AFTER_MILLIS] since the install. */
    fun shouldAsk(opens: Int, installedAt: Long, now: Long): Boolean =
        opens >= ASK_AFTER_OPENS && now - installedAt >= ASK_AFTER_MILLIS

    /** The player's answer, from the ask or Settings: saved, then applied to Crashlytics. */
    fun setEnabled(prefs: Prefs, on: Boolean) {
        prefs.crashReportsEnabled = on
        apply(on)
    }

    /** Puts the saved choice in force (every process start, from [PokeDaisyApp]). */
    fun apply(on: Boolean) {
        if (!available) return
        runCatching {
            val crashlytics = FirebaseCrashlytics.getInstance()
            crashlytics.setCrashlyticsCollectionEnabled(on)
            if (on) crashlytics.setCustomKey("version", BuildConfig.VERSION_NAME)
            else crashlytics.deleteUnsentReports()
        }.onFailure { Log.w("pokedaisy", "crash reports: couldn't apply ($on)", it) }
    }

    /**
     * What a crash report says about the game, set as one starts ([PokeDaisyActivity]'s loadRom): its name in the
     * library, file, console, header code, and how it was opened - [launchedFrom] is [LAUNCHED_FROM_LIBRARY] or the
     * app that sent it ([LaunchActivity]: a frontend's package). The ROM's SHA-1 (the exact build: version, language,
     * revision) follows from a background thread. Custom keys plus a log line, so a report also shows what ran before it.
     */
    fun gameStarted(context: Context, rom: File, romData: File, launchedFrom: String?) {
        if (!available) return
        runCatching {
            val c = FirebaseCrashlytics.getInstance()
            val platform = GameTitles.platform(context, rom)?.name ?: when {
                RomIdentity.isNds(romData) -> "NDS"
                RomIdentity.isGameBoy(romData) -> if (RomIdentity.isGameBoyColor(romData)) "GBC" else "GB"
                else -> "GBA"
            }
            val code = when (platform) {
                "NDS" -> RomIdentity.ndsGameCode(romData)
                "GBA" -> RomIdentity.gameCode(romData)
                else -> null
            }
            val game = GameTitles.defaultLabel(context, rom)
            val from = launchedFrom ?: "unknown"
            c.setCustomKey("game", game)
            c.setCustomKey("rom_file", rom.name)
            c.setCustomKey("platform", platform)
            c.setCustomKey("game_code", code.orEmpty())
            c.setCustomKey("rom_size", romData.length())
            c.setCustomKey("launched_from", from)
            c.setCustomKey("game_sha1", "")
            c.setCustomKey("game_identified", "")
            c.log("game started: $game ($platform ${code.orEmpty()}, ${rom.name}) from $from")
        }.onFailure { Log.w("pokedaisy", "crash reports: couldn't note the game", it) }
        Thread({
            val sha1 = RomIdentity.sha1(romData) ?: return@Thread
            runCatching {
                FirebaseCrashlytics.getInstance().setCustomKey("game_sha1", sha1)
                GameTitles.BY_SHA1[sha1]?.let { FirebaseCrashlytics.getInstance().setCustomKey("game_title", it) }
            }
        }, "pokedaisy-crash-sha1").apply { isDaemon = true; start() }
    }

    /** What the companion read the running game as, once it has decided ([kind]: a GameKind's name; [how]: e.g.
     * a BEST EFFORT match's title). */
    fun gameIdentified(kind: String, how: String? = null) {
        if (!available) return
        runCatching {
            val c = FirebaseCrashlytics.getInstance()
            val text = if (how.isNullOrEmpty()) kind else "$kind ($how)"
            c.setCustomKey("game_identified", text)
            c.log("game identified: $text")
        }
    }

    /** A line in the next report's log, for what the stack can't show (an ANR with an idle main thread: which window
     * had the focus, a move between screens). */
    fun breadcrumb(text: String) {
        if (!available) return
        runCatching { FirebaseCrashlytics.getInstance().log(text) }
    }

    /** [gameStarted]'s launchedFrom for a game opened from the library (the list, the grid, RECENTLY PLAYED, the launcher). */
    const val LAUNCHED_FROM_LIBRARY = "library"

    const val ASK_AFTER_OPENS = 3
    const val ASK_AFTER_MILLIS = 48L * 60 * 60 * 1000
}
