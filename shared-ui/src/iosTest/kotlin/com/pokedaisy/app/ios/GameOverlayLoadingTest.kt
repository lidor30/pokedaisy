package com.pokedaisy.app.ios

import androidx.compose.runtime.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.geometry.Rect
import com.pokedaisy.app.companion.data.deleteRecursively
import com.pokedaisy.app.companion.ui.GameOverlays
import com.pokedaisy.app.companion.ui.SkinImages
import com.pokedaisy.app.overlay.*
import kotlinx.coroutines.*
import okio.FileSystem
import kotlin.random.Random
import kotlin.test.*

class GameOverlayLoadingTest {
    private class EmptyApplier : AbstractApplier<Unit>(Unit) {
        override fun insertBottomUp(index: Int, instance: Unit) {}
        override fun insertTopDown(index: Int, instance: Unit) {}
        override fun move(from: Int, to: Int, count: Int) {}
        override fun remove(index: Int, count: Int) {}
        override fun onClear() {}
    }

    @Test fun decodedImagePublishesWithoutAnUnrelatedUiChange() = runBlocking {
        val root = FileSystem.SYSTEM_TEMPORARY_DIRECTORY / "overlay-loading-${Random.nextLong().toULong()}"
        val previousDecoder = SkinImages.decode
        val clock = BroadcastFrameClock()
        val recomposer = Recomposer(coroutineContext + clock)
        val composition = Composition(EmptyApplier(), recomposer)
        val loop = launch(clock) { recomposer.runRecomposeAndApplyChanges() }
        try {
            val store = OverlayStore(root)
            val png = byteArrayOf(-119,80,78,71,13,10,26,10,0,0,0,13,73,72,68,82,0,0,0,8,0,0,0,4,8,6,0,0,0)
            assertTrue(store.import(listOf(OverlayStore.Picked("background.png",png))) is OverlayStore.Result.Imported)
            val bitmap = ImageBitmap(8,4)
            Canvas(bitmap).drawRect(Rect(0f,0f,8f,4f),Paint().apply { color = Color(0xFF642882) })
            SkinImages.decode = { _,_,_ -> bitmap }
            val overlays = GameOverlays(store).apply { key = "user:background" }
            val published = CompletableDeferred<OverlayPlacement>()
            composition.setContent {
                overlays.Prepare(800,450,0)
                overlays.placement(800,450,false)?.let { if (!published.isCompleted) published.complete(it) }
            }
            withTimeout(5000) {
                var time=0L
                while (!published.isCompleted) { androidx.compose.runtime.snapshots.Snapshot.sendApplyNotifications();clock.sendFrame(time);time+=16_666_667;delay(10) }
            }
            assertFalse(published.await().onTop)
            assertEquals(Box(63f,0f,675f,450f),published.await().game)
        } finally {
            withContext(NonCancellable) {
                composition.dispose();recomposer.cancel();loop.cancelAndJoin()
                SkinImages.decode = previousDecoder
                root.deleteRecursively()
            }
        }
    }
}
