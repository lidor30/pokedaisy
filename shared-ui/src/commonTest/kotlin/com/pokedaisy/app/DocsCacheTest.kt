package com.pokedaisy.app

import com.pokedaisy.app.companion.data.*
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.HttpTimeout
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlin.random.Random
import kotlin.test.*
import okio.FileSystem
import okio.Path

class DocsCacheTest {
    private inline fun temporary(test: (Path) -> Unit) {
        val root = FileSystem.SYSTEM_TEMPORARY_DIRECTORY / "pokedaisy-manual-${Random.nextLong().toULong()}"
        root.mkdirs()
        try { test(root) } finally { root.deleteRecursively() }
    }
    private fun manual(rev: String, imageVersion: String = "one") = """{"version":1,"rev":"$rev","site":"https://example.test","sections":[],"pages":[{"slug":"start","title":"Start","blocks":[{"t":"note","c":[{"t":"ul","items":[[{"t":"img","src":"/manual.png","v":"$imageVersion"}]]}]}]}]}"""
    private fun image(version: String) = DocsApi.Block.Image("/manual.png", "example", null, 1, 1, version)

    @Test fun conditionalRefreshAndOfflineFailuresKeepTheLastValidatedManual() = temporary { dir ->
        var response = DocsCache.Reply(200, manual("one").encodeToByteArray(), "\"one\"")
        var offline = false
        val calls = mutableListOf<Map<String, String>>()
        val cache = DocsCache(dir, "test", { _, headers, timeout ->
            assertEquals(12_000L, timeout); calls += headers
            if (offline) throw DocsCache.Offline(Exception("offline"))
            response
        })
        assertIs<DocsApi.Refresh.Updated>(cache.refresh())
        assertFalse("If-None-Match" in calls.first())
        response = DocsCache.Reply(304)
        assertSame(DocsApi.Refresh.Unchanged, cache.refresh())
        assertEquals("\"one\"", calls.last()["If-None-Match"])
        assertEquals("PokeDaisy/test", calls.last()["User-Agent"])
        assertEquals("application/json", calls.last()["Accept"])
        offline = true
        assertTrue(assertIs<DocsApi.Refresh.Failed>(cache.refresh()).offline)
        offline = false
        for (bad in listOf(DocsCache.Reply(500), DocsCache.Reply(200, "broken".encodeToByteArray(), "bad"))) {
            response = bad
            assertFalse(assertIs<DocsApi.Refresh.Failed>(cache.refresh()).offline)
            assertEquals("one", cache.cached()?.rev)
            assertEquals("\"one\"", (dir / "docs.etag").readText())
        }
        assertEquals("one", DocsCache(dir, "test").cached()?.rev)
    }

    @Test fun corruptCacheDoesNotSendItsEtagAndAnUnsolicited304IsRejected() = temporary { dir ->
        (dir / "docs.json").writeText("partial"); (dir / "docs.etag").writeText("stale")
        var status = 304
        val cache = DocsCache(dir, "test", { _, headers, _ ->
            assertFalse("If-None-Match" in headers)
            DocsCache.Reply(status, manual("fixed").encodeToByteArray())
        })
        assertIs<DocsApi.Refresh.Failed>(cache.refresh())
        status = 200; assertIs<DocsApi.Refresh.Updated>(cache.refresh())
        assertEquals("fixed", cache.cached()?.rev); assertFalse((dir / "docs.etag").isFile)
    }

    @Test fun versionedImagesReuseInstalledCacheAndPruneNestedOldPictures() = temporary { dir ->
        var imageCalls = 0
        var revision = "one"
        val cache = DocsCache(dir, "test", { url, _, timeout ->
            if (url.endsWith(".png")) {
                assertEquals(15_000L, timeout); imageCalls++
                DocsCache.Reply(200, "picture-$revision".encodeToByteArray())
            } else DocsCache.Reply(200, manual(revision, revision).encodeToByteArray())
        })
        val first = assertIs<DocsApi.Refresh.Updated>(cache.refresh()).manual
        assertEquals("picture-one", cache.image(first, image("one"))?.decodeToString())
        val installed = (dir / "img").listFiles().single()
        val offline = DocsCache(dir, "test", { _, _, _ -> error("must reuse image") })
        assertEquals("picture-one", offline.image(first, image("one"))?.decodeToString())
        assertEquals(1, imageCalls)
        revision = "two"
        val second = assertIs<DocsApi.Refresh.Updated>(cache.refresh()).manual
        assertFalse(installed.isFile)
        assertEquals("picture-two", cache.image(second, image("two"))?.decodeToString())
        cache.pruneImages(second)
        assertEquals(1, (dir / "img").listFiles().size)
        assertEquals(2, imageCalls)
    }

    @Test fun ktorTransportCarriesHeadersStatusAndImageBytes() = temporary { dir ->
        var calls = 0
        val client = HttpClient(MockEngine { request ->
            calls++
            assertEquals("PokeDaisy/test", request.headers["User-Agent"])
            when (calls) {
                1 -> {
                    assertEquals("application/json", request.headers["Accept"])
                    respond(manual("ktor"), HttpStatusCode.OK, headersOf("ETag", "ktor-etag"))
                }
                2 -> {
                    assertEquals("ktor-etag", request.headers["If-None-Match"])
                    respond("", HttpStatusCode.NotModified)
                }
                else -> respond("png bytes", HttpStatusCode.OK)
            }
        }) { install(HttpTimeout) }
        try {
            val cache = DocsCache(dir, "test", { url, headers, timeout -> fetchDocs(client, url, headers, timeout) })
            val manual = assertIs<DocsApi.Refresh.Updated>(cache.refresh()).manual
            assertSame(DocsApi.Refresh.Unchanged, cache.refresh())
            assertEquals("png bytes", cache.image(manual, image("one"))?.decodeToString())
            assertEquals(3, calls)
        } finally { client.close() }
    }
}
