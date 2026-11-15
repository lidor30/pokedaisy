package com.pokedaisy.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.asImageBitmap
import com.pokedaisy.app.companion.ui.DocsSource
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/** The manual the app reads on Android: the docs API through [DocsStore], kept under filesDir/docs. */
class StoreDocsSource(context: Context) : DocsSource {
    private val app = context.applicationContext
    override fun cached() = DocsStore.cached(app)
    override fun refresh() = DocsStore.refresh(app).also { if (it is DocsApi.Refresh.Updated) DocsStore.pruneImages(app, it.manual) }
    override fun image(manual: DocsApi.Manual, image: DocsApi.Block.Image) = DocsStore.image(app, manual, image)?.asImageBitmap()
}

/** Fetches, keeps and serves the manual and its images (filesDir/docs). Blocking calls: run them off the UI thread. */
object DocsStore {
    private fun dir(context: Context) = File(context.filesDir, "docs").apply { mkdirs() }
    private fun manualFile(context: Context) = File(dir(context), "docs.json")
    private fun etagFile(context: Context) = File(dir(context), "docs.etag")

    /** The copy on disk, or null (never fetched, or unreadable). */
    fun cached(context: Context): DocsApi.Manual? =
        runCatching { DocsApi.parse(manualFile(context).readText()) }.getOrNull()

    /** Asks the site for the manual, sending the kept copy's ETag; a new one replaces the copy on disk. */
    fun refresh(context: Context, url: String = DocsApi.URL): DocsApi.Refresh = runCatching {
        val etag = etagFile(context).takeIf { it.isFile && manualFile(context).isFile }?.readText()
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 8000
            readTimeout = 12000
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", "PokeDaisy/${BuildConfig.VERSION_NAME}")
            etag?.let { setRequestProperty("If-None-Match", it) }
        }
        when (conn.responseCode) {
            HttpURLConnection.HTTP_NOT_MODIFIED -> DocsApi.Refresh.Unchanged
            HttpURLConnection.HTTP_OK -> {
                val body = conn.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
                val manual = DocsApi.parse(body)
                val tmp = File(dir(context), "docs.json.part")
                tmp.writeText(body)
                tmp.renameTo(manualFile(context))
                conn.getHeaderField("ETag")?.let { etagFile(context).writeText(it) } ?: etagFile(context).delete()
                DocsApi.Refresh.Updated(manual)
            }
            else -> DocsApi.Refresh.Failed(offline = false)
        }
    }.getOrElse { DocsApi.Refresh.Failed(offline = it is java.io.IOException) }

    /** An image block's picture: from disk if it was shown before, else fetched from the site and kept. */
    fun image(context: Context, manual: DocsApi.Manual, image: DocsApi.Block.Image): Bitmap? = runCatching {
        val file = imageFile(context, image)
        if (!file.isFile) {
            val conn = (URL(manual.site + image.src).openConnection() as HttpURLConnection).apply {
                connectTimeout = 8000
                readTimeout = 15000
                setRequestProperty("User-Agent", "PokeDaisy/${BuildConfig.VERSION_NAME}")
            }
            if (conn.responseCode != HttpURLConnection.HTTP_OK) return@runCatching null
            val tmp = File(file.parentFile, "${file.name}.part")
            conn.inputStream.use { input -> tmp.outputStream().use { input.copyTo(it) } }
            tmp.renameTo(file)
        }
        BitmapFactory.decodeFile(file.path)
    }.getOrNull()

    private fun imageFile(context: Context, image: DocsApi.Block.Image): File {
        val name = MessageDigest.getInstance("SHA-1").digest((image.src + "@" + image.version).toByteArray())
            .joinToString("") { "%02x".format(it) }.take(20) + ".png"
        return File(File(dir(context), "img").apply { mkdirs() }, name)
    }

    @Volatile private var prefetched = false

    /** Once per process, in the background: asks for a newer manual, so HELP has one even offline later. */
    fun prefetch(context: Context) {
        if (prefetched) return
        prefetched = true
        val app = context.applicationContext
        Thread({ (refresh(app) as? DocsApi.Refresh.Updated)?.let { pruneImages(app, it.manual) } }, "docs-prefetch").apply { isDaemon = true }.start()
    }

    /** Deletes kept pictures the manual no longer shows (an old version of a screenshot). */
    fun pruneImages(context: Context, manual: DocsApi.Manual) {
        val keep = mutableSetOf<String>()
        fun walk(blocks: List<DocsApi.Block>) {
            for (b in blocks) when (b) {
                is DocsApi.Block.Image -> keep += imageFile(context, b).name
                is DocsApi.Block.Bullets -> b.items.forEach(::walk)
                is DocsApi.Block.Note -> walk(b.blocks)
                else -> {}
            }
        }
        manual.pages.forEach { walk(it.blocks) }
        File(dir(context), "img").listFiles()?.filter { it.name !in keep }?.forEach { it.delete() }
    }
}
