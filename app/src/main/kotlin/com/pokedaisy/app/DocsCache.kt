package com.pokedaisy.app

import androidx.compose.ui.graphics.ImageBitmap
import com.pokedaisy.app.companion.data.*
import com.pokedaisy.app.companion.ui.DocsSource
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.timeout
import io.ktor.client.request.get
import io.ktor.client.request.header
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.*
import okio.ByteString.Companion.encodeUtf8
import okio.Path

/** Common offline manual and versioned image cache. All blocking operations run off the UI thread. */
class DocsCache(
    private val directory: Path,
    private val version: String,
    private val fetch: (String, Map<String, String>, Long) -> Reply = ::fetchDocs,
) {
    data class Reply(val status: Int, val body: ByteArray = byteArrayOf(), val etag: String? = null)
    class Offline(cause: Throwable) : Exception(cause)
    private val lock = SynchronizedObject()
    private val manualFile = directory / "docs.json"
    private val etagFile = directory / "docs.etag"
    private var prefetched = false

    fun cached(): DocsApi.Manual? = synchronized(lock) {
        runCatching { DocsApi.parse(manualFile.readText()) }.getOrNull()
    }

    // The files only are under [lock]; a fetch (up to ~20 s offline) never holds it, or HELP opened during the launch's
    // prefetch sat on LOADING with the cached manual right there.
    fun refresh(url: String = DocsApi.URL): DocsApi.Refresh = runCatching {
        val headers = synchronized(lock) {
            headers() + mapOf("Accept" to "application/json") +
                (if (cached() != null && etagFile.isFile) mapOf("If-None-Match" to etagFile.readText()) else emptyMap())
        }
        val reply = fetch(url, headers, 12_000)
        synchronized(lock) {
            when (reply.status) {
                304 -> if (cached() != null) DocsApi.Refresh.Unchanged else DocsApi.Refresh.Failed(offline = false)
                200 -> {
                    val text = reply.body.decodeToString()
                    val manual = DocsApi.parse(text)
                    manualFile.writeTextAtomically(text)
                    runCatching {
                        if (reply.etag == null) etagFile.delete() else etagFile.writeTextAtomically(reply.etag)
                    }.onFailure { runCatching { etagFile.delete() } }
                    runCatching { pruneImages(manual) }
                    DocsApi.Refresh.Updated(manual)
                }
                else -> DocsApi.Refresh.Failed(offline = false)
            }
        }
    }.getOrElse { DocsApi.Refresh.Failed(offline = it is Offline || it is okio.IOException) }

    fun image(manual: DocsApi.Manual, image: DocsApi.Block.Image): ByteArray? = runCatching {
        val file = imageFile(image)
        synchronized(lock) { if (file.isFile) return@runCatching file.readBytes() }
        val reply = fetch(manual.site + image.src, headers(), 15_000)
        if (reply.status != 200) return@runCatching null
        synchronized(lock) {
            file.writeAtomically(reply.body)
            file.readBytes()
        }
    }.getOrNull()

    /** One refresh per cache owner, without a platform thread or timer. HELP refreshes independently afterward. */
    fun prefetch() = synchronized(lock) {
        if (prefetched) return@synchronized
        prefetched = true
        CoroutineScope(Dispatchers.Default).launch { refresh() }
        Unit
    }

    fun pruneImages(manual: DocsApi.Manual) = synchronized(lock) {
        val keep = mutableSetOf<String>()
        fun walk(blocks: List<DocsApi.Block>) {
            for (block in blocks) when (block) {
                is DocsApi.Block.Image -> keep += imageFile(block).name
                is DocsApi.Block.Bullets -> block.items.forEach(::walk)
                is DocsApi.Block.Note -> walk(block.blocks)
                else -> {}
            }
        }
        manual.pages.forEach { walk(it.blocks) }
        (directory / "img").listFiles().filter { it.name !in keep }.forEach { it.delete() }
    }

    private fun headers() = mapOf("User-Agent" to "PokeDaisy/$version")
    private fun imageFile(image: DocsApi.Block.Image): Path = directory / "img" /
        ((image.src + "@" + image.version).encodeUtf8().sha1().hex().take(20) + ".png")

    companion object {
        private val lock = SynchronizedObject()
        private val stores = mutableMapOf<Pair<Path, String>, DocsCache>()
        /** Hosts can be recreated while downloads run; one owner serializes each installed cache. */
        fun at(directory: Path, version: String): DocsCache = synchronized(lock) {
            stores.getOrPut(directory to version) { DocsCache(directory, version) }
        }
    }
}

/** Native image decoding is the only platform operation in the shared docs presenter bridge. */
class CachedDocsSource(private val cache: DocsCache, private val decode: (ByteArray) -> ImageBitmap?) : DocsSource {
    override fun cached() = cache.cached()
    override fun refresh() = cache.refresh()
    override fun image(manual: DocsApi.Manual, image: DocsApi.Block.Image) = cache.image(manual, image)?.let(decode)
}

private val docsClient by lazy { HttpClient { install(HttpTimeout) { connectTimeoutMillis = 8_000 } } }
private fun fetchDocs(url: String, headers: Map<String, String>, socketTimeout: Long): DocsCache.Reply =
    fetchDocs(docsClient, url, headers, socketTimeout)

internal fun fetchDocs(client: HttpClient, url: String, headers: Map<String, String>, socketTimeout: Long): DocsCache.Reply = runBlocking {
    try {
        val response = client.get(url) {
            headers.forEach { (key, value) -> header(key, value) }
            timeout { socketTimeoutMillis = socketTimeout; requestTimeoutMillis = socketTimeout + 8_000 }
        }
        DocsCache.Reply(response.status.value, if (response.status.value == 200) response.body<ByteArray>() else byteArrayOf(),
            response.headers["ETag"])
    } catch (cancelled: CancellationException) { throw cancelled }
      catch (error: Exception) { throw DocsCache.Offline(error) }
}
