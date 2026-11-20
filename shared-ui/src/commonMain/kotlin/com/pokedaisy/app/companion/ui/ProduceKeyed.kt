package com.pokedaisy.app.companion.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.ProduceStateScope
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.CoroutineContext

/**
 * [androidx.compose.runtime.produceState] that starts over from [initialValue] whenever one of [keys] changes.
 * Compose's own remembers its value once and only restarts the producer, so a producer that loads only while the
 * value is null ("cached, else load") went on showing the old key's value: the DEX's AREA said "Not found in the
 * wild" for an entry seen for the first time (GitHub #229) - its species was 0 for a frame before the entry loaded,
 * and that species' empty list then stood.
 */
@Composable
fun <T> produceKeyedState(initialValue: T, vararg keys: Any?, producer: suspend ProduceStateScope<T>.() -> Unit): State<T> {
    val result = remember(*keys) { mutableStateOf(initialValue) }
    LaunchedEffect(*keys) { KeyedProduceScope(result, coroutineContext).producer() }
    return result
}

private class KeyedProduceScope<T>(state: MutableState<T>, override val coroutineContext: CoroutineContext) :
    ProduceStateScope<T>, MutableState<T> by state {
    override suspend fun awaitDispose(onDispose: () -> Unit): Nothing {
        try {
            suspendCancellableCoroutine<Nothing> { }
        } finally {
            onDispose()
        }
    }
}
