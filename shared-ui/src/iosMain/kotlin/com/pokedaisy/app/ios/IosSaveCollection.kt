package com.pokedaisy.app.ios

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.pokedaisy.app.companion.data.save.MonRef
import com.pokedaisy.app.companion.data.save.SaveLibrary
import com.pokedaisy.app.companion.i18n.tr
import com.pokedaisy.app.companion.ui.CollectionScreen
import com.pokedaisy.app.companion.ui.CollectionState
import com.pokedaisy.app.companion.ui.OptionConfirm
import com.pokedaisy.app.companion.ui.SaveEditorText
import com.pokedaisy.app.companion.ui.rememberGbaTextMetrics
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private class CollectedGame(val path: String, val label: String, val save: IosGen3Save.Ready)

@Composable
internal fun IosSaveCollection(
    paths: List<String>, label: (String) -> String, state: CollectionState, onClose: () -> Unit,
    onOpen: (String, MonRef) -> Unit, onTrade: (String, MonRef) -> Unit,
) = SaveEditorText {
    val m = rememberGbaTextMetrics()
    var games by remember(state) { mutableStateOf<List<CollectedGame>>(emptyList()) }
    var error by remember(state) { mutableStateOf<String?>(null) }
    LaunchedEffect(paths, state) {
        state.sides = null
        val titles = paths.associateWith(label)
        val collection = withContext(Dispatchers.Default) {
            if (!recoverIosSaveTrades()) return@withContext null
            SaveLibrary.collection(editableIosGames(paths), { path ->
                val name = titles.getValue(path)
                (readIosGen3Save(path, name) as? IosGen3Save.Ready)?.let { CollectedGame(path, name, it) }
            }, { it.save.tradeSide(it.label) })
        }
        if (collection == null) {
            error = iosSaveTradeRecoveryError()
        } else {
            error = null
            games = collection.sources
            state.load(collection.sides, collection.items)
        }
    }
    CollectionScreen(state, m, onClose, icons = { games.getOrNull(it)?.save?.icons },
        onOpen = { item -> games.getOrNull(item.source)?.let { onOpen(it.path, item.at) } },
        onTrade = { item -> games.getOrNull(item.source)?.let { onTrade(it.path, item.at) } },
    )
    error?.let {
        OptionConfirm(tr("COLLECTION"), it, tr("CLOSE"), m, onConfirm = onClose, onDismiss = onClose, cancelLabel = null)
    }
}
