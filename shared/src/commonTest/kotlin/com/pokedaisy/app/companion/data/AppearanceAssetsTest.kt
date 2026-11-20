package com.pokedaisy.app.companion.data

import okio.FileSystem
import kotlin.random.Random
import kotlin.test.*

class AppearanceAssetsTest {
    private fun withAssets(test: (AppearanceAssets) -> Unit) {
        val root = FileSystem.SYSTEM_TEMPORARY_DIRECTORY / "appearance-test-${Random.nextLong().toULong()}"
        FileSystem.SYSTEM.createDirectories(root)
        try { test(AppearanceAssets(root)) } finally { FileSystem.SYSTEM.deleteRecursively(root) }
    }
    private fun blank() = ByteArray(0xC0).also {
        "BPRE".encodeToByteArray().copyInto(it, 0xAC); it[0xB2] = 0x96.toByte()
    }

    @Test fun repeatedInventorySkipsCachedFilesAndChangedVersionsAreRevisited() = withAssets { assets ->
        var reads = 0; var headers = 0
        fun source(version: Int) = AppearanceAssets.Source("other", "other|192|$version", "other.gba",
            { headers++; "BPRE" }) { reads++; blank() }
        assets.scanMissing("running", listOf(source(1))); assertEquals(1, reads)
        assets.scanMissing("running", listOf(source(1))); assertEquals(1, reads); assertEquals(1, headers)
        assets.scanMissing("running", listOf(source(2))); assertEquals(2, reads); assertEquals(2, headers)
        assets.scanMissing("other", listOf(source(3))); assertEquals(2, reads)
    }

    @Test fun retiredReadIsNotRecordedAsScannedAndUnknownHeadersDoNotReadTheRom() = withAssets { assets ->
        var current = true; var reads = 0
        val source = AppearanceAssets.Source("other", "other|192|1", "other.gba", { "BPRE" }) {
            reads++; current = false; blank()
        }
        assets.scanMissing("running", listOf(source), { current }); assertEquals(1, reads)
        current = true
        assets.scanMissing("running", listOf(source), { current }); assertEquals(2, reads)
        val unknown = AppearanceAssets.Source("unknown", "unknown|192|1", "unknown.bin", { null }) { error("Unsupported source read") }
        assets.scanMissing("running", listOf(unknown))
    }

    @Test fun aFailedSourceDoesNotPreventTheNextOwnedRomFromBeingScanned() = withAssets { assets ->
        var reads = 0
        val broken = AppearanceAssets.Source("broken", "broken|1|1", "broken.gba", { "BPRE" }) { error("unreadable") }
        val valid = AppearanceAssets.Source("other", "other|192|1", "other.gba", { "BPRE" }) { reads++; blank() }
        assets.scanMissing("running", listOf(broken, valid)); assertEquals(1, reads)
        assets.scanMissing("running", listOf(broken, valid)); assertEquals(1, reads)
    }
}
