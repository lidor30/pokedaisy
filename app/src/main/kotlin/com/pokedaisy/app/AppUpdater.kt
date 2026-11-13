package com.pokedaisy.app

import org.json.JSONArray
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Updates from the app's GitHub releases: the newest published release (not a
 * draft or a pre-release - those are for testing) that carries an `.apk` and
 * whose tag is a higher version than this build's versionName. Only talks to api.github.com and GitHub's download
 * host; no account, no key.
 */
object AppUpdater {

    /** Build-configurable so a fork / translation build can point the updater at its own
     * releases instead of upstream's; an empty value turns the check off entirely. */
    val REPO: String get() = BuildConfig.UPDATE_REPO
    val RELEASES_PAGE: String get() = if (REPO.isEmpty()) "" else "https://github.com/$REPO/releases"

    class Release(
        val version: String,
        val notes: String,
        val apkUrl: String,
        val apkSize: Long,
        val pageUrl: String,
    )

    /** The newest release that is newer than [current], or null (none, offline, or updates off). Blocking. */
    fun check(current: String): Release? {
        if (REPO.isEmpty()) return null
        return runCatching {
            val conn = (URL("https://api.github.com/repos/$REPO/releases?per_page=20").openConnection() as HttpURLConnection).apply {
                connectTimeout = 8000
                readTimeout = 8000
                setRequestProperty("Accept", "application/vnd.github+json")
                setRequestProperty("User-Agent", "PokeDaisy/$current")
            }
            val body = conn.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
            newest(JSONArray(body))
        }.getOrNull()?.takeIf { isNewer(it.version, current) }
    }

    /** The highest-versioned published, non-pre-release release with an APK in [releases] (GitHub's JSON). */
    fun newest(releases: JSONArray): Release? {
        val found = (0 until releases.length()).mapNotNull { i ->
            val r = releases.getJSONObject(i)
            if (r.optBoolean("draft") || r.optBoolean("prerelease")) return@mapNotNull null
            val assets = r.optJSONArray("assets") ?: return@mapNotNull null
            val apk = (0 until assets.length()).map { assets.getJSONObject(it) }
                .firstOrNull { it.optString("name").endsWith(".apk", ignoreCase = true) } ?: return@mapNotNull null
            Release(
                version = r.optString("tag_name").removePrefix("v"),
                notes = r.optString("body").trim(),
                apkUrl = apk.optString("browser_download_url"),
                apkSize = apk.optLong("size"),
                pageUrl = r.optString("html_url", RELEASES_PAGE),
            )
        }
        return found.maxWithOrNull { a, b -> compareVersions(a.version, b.version) }
    }

    fun isNewer(candidate: String, current: String): Boolean = compareVersions(candidate, current) > 0

    /**
     * Semantic-version order: `1.0.0-beta.1` < `1.0.0-beta.2` < `1.0.0-rc.1` < `1.0.0`
     * < `1.0.1`. Numeric pre-release parts compare as numbers, the rest as text; a
     * missing part counts as 0 (`1.0` == `1.0.0`). Build metadata (`+…`) is ignored.
     */
    fun compareVersions(a: String, b: String): Int {
        fun split(v: String): Pair<List<Int>, List<String>> {
            val clean = v.trim().removePrefix("v").substringBefore('+')
            val core = clean.substringBefore('-').split('.').map { it.toIntOrNull() ?: 0 }
            val pre = if ('-' in clean) clean.substringAfter('-').split('.') else emptyList()
            return core to pre
        }
        val (ca, pa) = split(a)
        val (cb, pb) = split(b)
        for (i in 0 until maxOf(ca.size, cb.size)) {
            val d = ca.getOrElse(i) { 0 }.compareTo(cb.getOrElse(i) { 0 })
            if (d != 0) return d
        }
        // A release outranks any pre-release of the same version.
        if (pa.isEmpty() || pb.isEmpty()) return pb.size.coerceAtMost(1) - pa.size.coerceAtMost(1)
        for (i in 0 until maxOf(pa.size, pb.size)) {
            val x = pa.getOrNull(i) ?: return -1
            val y = pb.getOrNull(i) ?: return 1
            val xn = x.toIntOrNull()
            val yn = y.toIntOrNull()
            val d = when {
                xn != null && yn != null -> xn.compareTo(yn)
                xn != null -> -1
                yn != null -> 1
                else -> x.compareTo(y)
            }
            if (d != 0) return d
        }
        return 0
    }

    /** Downloads [release]'s APK to [dest] (swapped in only once complete and the
     * size GitHub reported). [onProgress] gets 0..1. Blocking. */
    fun download(release: Release, dest: File, onProgress: (Float) -> Unit): Boolean = runCatching {
        dest.parentFile?.mkdirs()
        val tmp = File(dest.parentFile, "${dest.name}.part")
        val conn = (URL(release.apkUrl).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10000
            readTimeout = 20000
            instanceFollowRedirects = true
        }
        val total = conn.contentLengthLong.takeIf { it > 0 } ?: release.apkSize
        var read = 0L
        conn.inputStream.use { input ->
            tmp.outputStream().use { out ->
                val buf = ByteArray(1 shl 16)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    read += n
                    if (total > 0) onProgress((read.toFloat() / total).coerceIn(0f, 1f))
                }
            }
        }
        val ok = read > 0 && (release.apkSize <= 0 || read == release.apkSize) && tmp.renameTo(dest)
        if (!ok) tmp.delete()
        ok
    }.getOrDefault(false)
}
