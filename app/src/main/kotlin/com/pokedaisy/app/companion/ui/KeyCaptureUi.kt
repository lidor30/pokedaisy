package com.pokedaisy.app.companion.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.pokedaisy.app.companion.KeyCapture
import com.pokedaisy.app.companion.i18n.tk
import com.pokedaisy.app.companion.i18n.tr

/** What a waiting [KeyCapture] asks for, one line: "Press the button on your device...". */
fun keyCaptureHint(r: KeyCapture.Request) =
    if (r.chord) tr("Hold the keys together, then let go. BACK or Escape cancels.")
    else tr("Press the button on your device. BACK or Escape cancels.")

/** A waiting row's value: what to do, then the keys held so far. */
fun keyCaptureValue(capture: KeyCapture, r: KeyCapture.Request) =
    if (capture.keys.isEmpty()) (if (r.chord) tr("PRESS KEYS…") else tr("PRESS A BUTTON…")) else KeyCapture.chordLabel(capture.keys)

/**
 * One rebindable row, bound in place: a tap waits for keys (the row turns white and reads PRESS A BUTTON…, then the
 * keys as they're held), a second tap cancels. While it waits, a line under it says what to do, with CANCEL (and
 * CLEAR, where [KeyCapture.Request.onClear] allows it). Top-screen Settings and first-time setup use it; the
 * companion's rows (OptionRows) show the same value and [KeyCaptureBar].
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun KeyBindRow(
    label: String,
    keys: List<String>?,
    capture: KeyCapture,
    m: GbaTextMetrics,
    small: GbaTextMetrics,
    divider: Boolean,
    enabled: Boolean = true,
    labelWeight: Float = 0.5f,
    /** An [OptionBadge] after the label (a hotkey's NEW). */
    labelBadge: String? = null,
    /** A grey line under the label: what the button does (English; OptionLine translates it). */
    subtitle: String? = null,
    request: () -> KeyCapture.Request,
) {
    val r = capture.request?.takeIf { capture.isFor(label) }
    OptionLine(
        tr(label), if (r != null) keyCaptureValue(capture, r) else KeyCapture.labels(keys),
        selected = r != null, m, height = m.rowHeight * 1.2f + (if (subtitle != null) m.lineHeight else 0.dp), labelWeight = labelWeight,
        divider = divider && r == null, enabled = enabled, labelBadge = labelBadge, subtitle = subtitle,
    ) { if (r != null) capture.cancel() else capture.start(request()) }
    if (r != null) {
        // A row low in the list: its line scrolls up into view, or CANCEL would open below the fold.
        val reveal = remember { BringIntoViewRequester() }
        LaunchedEffect(r) { androidx.compose.runtime.withFrameNanos { }; reveal.bringIntoView() }
        KeyCaptureBar(capture, r, m, small, Modifier.bringIntoViewRequester(reveal).padding(horizontal = m.u * 4, vertical = m.u * 3))
    }
}

/** What to do while [r] waits, and its CLEAR / CANCEL buttons (a tap needs no key: a device with none can still leave). */
@Composable
fun KeyCaptureBar(capture: KeyCapture, r: KeyCapture.Request, m: GbaTextMetrics, small: GbaTextMetrics, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth()) {
        GbaText(keyCaptureHint(r), OptionColors.label, OptionColors.labelShadow, small, maxLines = Int.MAX_VALUE)
        Spacer(Modifier.height(m.u * 3))
        Row(horizontalArrangement = Arrangement.spacedBy(m.u * 8), modifier = Modifier.fillMaxWidth()) {
            if (r.onClear != null) OptionButton(tr("CLEAR {0}", tr(r.title)), m, modifier = Modifier.weight(1f), onClick = capture::clear)
            OptionButton(tk("CANCEL"), m, modifier = Modifier.weight(1f), emphasis = r.onClear == null, onClick = capture::cancel)
        }
    }
}

/**
 * Over the game while the companion waits for keys (its rows are on the other screen, and the keys go to this one):
 * what's being bound, what to press and what's held so far, CLEAR / CANCEL for a touch. Nothing while none waits.
 */
@Composable
fun KeyCapturePopup(capture: KeyCapture) {
    val r = capture.request ?: return
    val m = rememberGbaTextMetrics()
    val small = rememberGbaTextMetrics(1f)
    val swallow = remember { MutableInteractionSource() }
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xB0000000))
            // Taps stop here: the touch controls under it is the game's.
            .clickable(interactionSource = swallow, indication = null, onClick = {})
            .padding(16.dp),
    ) {
        Column(Modifier.widthIn(max = 560.dp)) {
            OptionTitleWindow(tr(r.title), m)
            Spacer(Modifier.height(m.u * 4))
            OptionListWindow(m, Modifier.fillMaxWidth()) {
                Column(Modifier.padding(horizontal = m.u * 6, vertical = m.u * 4)) {
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        GbaText(keyCaptureValue(capture, r), OptionColors.value, OptionColors.valueShadow, m)
                    }
                    Spacer(Modifier.height(m.u * 4))
                    KeyCaptureBar(capture, r, m, small)
                }
            }
        }
    }
}
