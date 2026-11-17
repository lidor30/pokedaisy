package com.pokedaisy.app.companion.ui

import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import com.pokedaisy.app.cheats.Cheat
import com.pokedaisy.app.cheats.CheatCheck
import com.pokedaisy.app.cheats.CheatCodes
import com.pokedaisy.app.cheats.CheatStore
import com.pokedaisy.app.cheats.CheatType
import com.pokedaisy.app.companion.i18n.tk
import com.pokedaisy.app.companion.i18n.tr

/** The existing Android code-entry form, shared with hosts that can type codes beside the game. */
@Composable
fun CheatAddForm(m: GbaTextMetrics, small: GbaTextMetrics, ds: Boolean, nextNumber: Int,
                 onCancel: () -> Unit, onAdd: (Cheat) -> Unit, modifier: Modifier = Modifier) {
    var typesOpen by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    var type by remember { mutableStateOf(CheatType.AUTO) }
    var error by remember { mutableStateOf<String?>(null) }
    Box(modifier) {
        Column(modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
            PageHint(
                if (ds) tr("Type or paste an Action Replay DS code, one line or many.")
                else tr("Type or paste a GameShark, Action Replay or CodeBreaker code, one line or many. AUTO works out which it is; pick the type when a code doesn't take."),
                m, small,
            )
            OptionListWindow(m, Modifier.fillMaxWidth()) {
                Column(Modifier.padding(horizontal = m.u * 4)) {
                    GbaText(tr("NAME"), OptionColors.label, OptionColors.labelShadow, m, Modifier.padding(vertical = m.u * 2))
                    OptionTextField(name, { name = it; error = null }, small, placeholder = tr("e.g. INFINITE MONEY"))
                    Spacer(Modifier.height(m.u * 4))
                    GbaText(tr("CODE"), OptionColors.label, OptionColors.labelShadow, m, Modifier.padding(vertical = m.u * 2))
                    OptionTextField(code, { code = it.uppercase(); error = null }, small, placeholder = "XXXXXXXX YYYYYYYY", minLines = 3, code = true)
                    Spacer(Modifier.height(m.u * 2))
                    // Back by the list row's own inset, so TYPE lines up with NAME and CODE. A DS game has one kind of code.
                    if (!ds) OptionLine(tk("TYPE"), type.label, selected = false, m, Modifier.offset(x = -m.u * 4), height = m.rowHeight * 1.2f) {
                        typesOpen = true
                    }
                    error?.let {
                        GbaText(it, OptionColors.value, OptionColors.valueShadow, small, Modifier.padding(top = m.u * 4), maxLines = 4)
                    }
                    Row(Modifier.padding(vertical = m.u * 4), horizontalArrangement = Arrangement.spacedBy(m.u * 4)) {
                        OptionButton(tk("CANCEL"), m, onClick = onCancel)
                        OptionButton(tk("ADD"), m, emphasis = true, enabled = code.isNotBlank(), onClick = {
                            val lines = CheatCodes.normalize(code)
                            val label = name.trim().ifEmpty { tr("CHEAT {0}", nextNumber) }
                            val r = CheatCheck.check(Cheat(label, lines), type, ds = ds)
                            val ok = r.cheat
                            if (ok == null) {
                                error = when {
                                    r.bad.isEmpty() -> tr("That isn't a code.")
                                    ds -> tr("{0} isn't an Action Replay DS line.", r.bad.joinToString(", "))
                                    type == CheatType.AUTO -> tr("mGBA can't read {0}.", r.bad.joinToString(", "))
                                    else -> tr("mGBA can't read {0} as a {1} code.", r.bad.joinToString(", "), tr(type.label))
                                }
                            } else {
                                // Typed in to be used: on (the master switch still decides).
                                onAdd(ok.copy(enabled = true))
                            }
                        })
                    }
                }
            }
        }
        if (typesOpen) OptionSelector(tk("TYPE"), CheatType.entries, type, { it.label }, m,
            onPick = { type = it; error = null; typesOpen = false }, onDismiss = { typesOpen = false })
    }
}

/** Shared result of importing a RetroArch or mGBA cheat file. */
fun cheatImportNotice(r: CheatStore.Import?): Pair<String, String> = when {
    r == null -> tk("CAN'T READ THAT FILE") to tr("Pick a RetroArch .cht or an mGBA .cheats file.")
    r.added == 0 && r.badCheats == 0 && r.duplicates == 0 ->
        tk("NO CHEATS FOUND") to tr("That file has no cheats in a format PokeDaisy reads (RetroArch .cht, mGBA .cheats).")
    else -> (if (r.added == 0) tk("NO CHEATS ADDED") else tr("{0} CHEATS ADDED", r.added)) to listOfNotNull(
        tr("They start OFF: tap one to turn it on.").takeIf { r.added > 0 },
        tr("{0} left out: mGBA can't read {1} of their code lines.", r.badCheats, r.badLines).takeIf { r.badCheats > 0 },
        tr("{0} were already in the list.", r.duplicates).takeIf { r.duplicates > 0 },
    ).joinToString(" ")
}
