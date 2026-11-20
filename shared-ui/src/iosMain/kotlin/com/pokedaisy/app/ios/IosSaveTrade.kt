package com.pokedaisy.app.ios

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import com.pokedaisy.app.SaveEditFiles
import com.pokedaisy.app.SaveTradeJournal
import com.pokedaisy.app.companion.data.save.MonRef
import com.pokedaisy.app.companion.i18n.tr
import com.pokedaisy.app.companion.ui.CompanionBackHandler
import com.pokedaisy.app.companion.ui.OptionConfirm
import com.pokedaisy.app.companion.ui.OptionSelector
import com.pokedaisy.app.companion.ui.SaveEditorText
import com.pokedaisy.app.companion.ui.TradeScreen
import com.pokedaisy.app.companion.ui.TradeState
import com.pokedaisy.app.companion.ui.rememberGbaTextMetrics
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal data class IosTradeRequest(
    val first: String? = null, val second: String? = null,
    val firstPick: MonRef? = null, val secondPick: MonRef? = null,
)

private class OpenTrade(val a: IosGen3Save.Ready, val b: IosGen3Save.Ready, val state: TradeState)

@Composable
internal fun IosSaveTrade(
    paths: List<String>, request: IosTradeRequest, title: (String) -> String, onClose: () -> Unit, onSaved: () -> Unit,
) = SaveEditorText {
    val m = rememberGbaTextMetrics()
    val scope = rememberCoroutineScope()
    var candidates by remember(paths) { mutableStateOf<List<String>?>(null) }
    var from by remember(request) { mutableStateOf(request.first) }
    var to by remember(request) { mutableStateOf(request.second) }
    var open by remember { mutableStateOf<OpenTrade?>(null) }
    var saving by remember { mutableStateOf(false) }
    var reload by remember { mutableIntStateOf(0) }
    var notice by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(paths) {
        candidates = withContext(Dispatchers.Default) { editableIosGames(paths) }
    }

    fun chooseAgain() {
        to = null
        open = null
    }

    val source = from
    val partner = to
    LaunchedEffect(source, partner, reload) {
        if (source == null || partner == null) return@LaunchedEffect
        val aName = title(source)
        val bName = title(partner)
        val (a, b) = withContext(Dispatchers.Default) {
            readIosGen3Save(source, aName) to readIosGen3Save(partner, bName)
        }
        if (a is IosGen3Save.Ready && b is IosGen3Save.Ready && a.path != b.path) {
            val state = TradeState(a.tradeSide(aName), b.tradeSide(bName))
            if (reload == 0) {
                request.firstPick?.let { state.focus(it, firstSide = true) }
                request.secondPick?.let { state.focus(it, firstSide = false) }
            }
            open = OpenTrade(a, b, state)
        } else {
            notice = (a as? IosGen3Save.Failed)?.message ?: (b as? IosGen3Save.Failed)?.message
                ?: tr("Both games use the same save file")
            chooseAgain()
        }
    }

    val trade = open
    if (trade != null) {
        CompanionBackHandler { if (!saving) trade.state.back(onClose) }
        TradeScreen(trade.state, m, onBack = onClose, saving = saving, iconsA = trade.a.icons, iconsB = trade.b.icons, onSave = {
            if (!saving) {
                val edits = runCatching {
                    fun side(source: IosGen3Save.Ready, updated: ByteArray) =
                        SaveTradeJournal.Side(SaveEditFiles.Edit(source.path, source.source.bytes, updated), source.stateKey)
                    side(trade.a, trade.state.a.editor.build()) to side(trade.b, trade.state.b.editor.build())
                }.getOrNull()
                if (edits == null) {
                    notice = tr("Can't write the save")
                } else {
                    saving = true
                    scope.launch {
                        val result = withContext(NonCancellable + Dispatchers.Default) {
                            runCatching { commitIosSaveTrade(edits.first, edits.second) }
                                .getOrElse { SaveEditFiles.Result.Failed(tr("Can't write the save")) }
                        }
                        saving = false
                        when (result) {
                            is SaveEditFiles.Result.Written -> {
                                notice = tr("Saved. The previous save is in {0}", result.backup.parent?.name ?: "pokedaisy-backups")
                                open = null
                                reload++
                                onSaved()
                            }
                            is SaveEditFiles.Result.Failed -> notice = result.reason
                        }
                    }
                }
            }
        })
    } else {
        val games = candidates
        val choices = if (source == null && games.orEmpty().size < 2) emptyList() else games.orEmpty().filter { it != source }
        if (games == null || partner != null || choices.isEmpty()) {
            val message = if (games == null || partner != null) tr("LOADING…")
                else tr("No other game here has a save the save editor can read")
            val close = if (partner != null) ({ chooseAgain() }) else onClose
            OptionConfirm(tr("TRADE SAVES"), message, tr("CLOSE"), m,
                onConfirm = close, onDismiss = close, cancelLabel = null)
        } else {
            OptionSelector(if (source == null) tr("TRADE FROM") else tr("TRADE WITH"), choices, choices.first(), title, m,
                onPick = { if (source == null) from = it else to = it },
                onDismiss = { if (source == null || request.first != null) onClose() else from = null })
        }
    }
    IosSaveWriteStatus(tr("TRADE SAVES"), saving, notice, clearNotice = { notice = null })
}
