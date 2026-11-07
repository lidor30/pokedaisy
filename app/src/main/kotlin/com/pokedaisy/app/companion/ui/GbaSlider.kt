package com.pokedaisy.app.companion.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.unit.dp
import com.pokedaisy.app.companion.i18n.tk
import com.pokedaisy.app.companion.ui.theme.gbaFocusRing
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.translate
import kotlin.math.roundToInt

/**
 * A settings row's value as a slider ([SettingRow.slider], drawn by [OptionLine]): [value] in [range], moved in [step]s.
 * A tap or a drag on the bar sets it, and LEFT / RIGHT step it while the row has the D-pad (top-screen Settings, the
 * companion's window or its focus mode). [onChange] hears every new value, while the finger moves too.
 */
class SettingSlider(val value: Int, val range: IntRange, val step: Int, val onChange: (Int) -> Unit) {
    /** LEFT / RIGHT as a step down / up; true when it was one (taken, so focus doesn't move off the row). */
    fun onKey(e: KeyEvent): Boolean {
        val dir = when (e.key) {
            Key.DirectionLeft -> -1
            Key.DirectionRight -> 1
            else -> return false
        }
        if (e.type == KeyEventType.KeyDown) set(value + dir * step)
        return true
    }

    /** [v] snapped to a step and kept in [range]; heard only if it changed. */
    fun set(v: Int) {
        val snapped = (range.first + ((v - range.first) / step.toFloat()).roundToInt() * step).coerceIn(range)
        if (snapped != value) onChange(snapped)
    }

    /** Where [value] sits along the bar, 0..1. */
    val fraction: Float get() = (value - range.first) / (range.last - range.first).toFloat()
}

/**
 * [SettingSlider]'s bar and knob in the OPTION look, whole GBA pixels ([m]'s u): a framed white track filled in the value
 * red up to a round white knob. [enabled] false greys the fill and ignores touches.
 */
@Composable
fun GbaSlider(
    slider: SettingSlider, m: GbaTextMetrics, enabled: Boolean, modifier: Modifier = Modifier,
    /** The knob's diameter and the bar's height, in GBA pixels: the popup's slider is bigger. */
    knob: Int = KNOB, track: Int = TRACK,
) {
    val current by rememberUpdatedState(slider)
    // The knob's diameter, in GBA pixels; the bar runs between the knob's centre at each end.
    val knobUnits = knob
    fun valueAt(x: Float, width: Float, px: Float) {
        val knob = knobUnits * px
        val frac = ((x - knob / 2) / (width - knob)).coerceIn(0f, 1f)
        val s = current
        s.set((s.range.first + frac * (s.range.last - s.range.first)).roundToInt())
    }
    val frame = OptionColors.titleLayers
    val paper = OptionColors.titleFill
    val fill = if (enabled) OptionColors.value else OptionColors.muted
    Canvas(
        modifier
            .height(m.u * knobUnits)
            .semantics {
                val s = current
                progressBarRangeInfo = ProgressBarRangeInfo(s.value.toFloat(), s.range.first.toFloat()..s.range.last.toFloat(), (s.range.last - s.range.first) / s.step - 1)
                setProgress { v -> current.set(v.roundToInt()); true }
            }
            .then(
                if (!enabled) Modifier
                else Modifier
                    .pointerInput(Unit) {
                        val px = m.u.toPx()
                        detectTapGestures(onTap = { valueAt(it.x, size.width.toFloat(), px) })
                    }
                    .pointerInput(Unit) {
                        val px = m.u.toPx()
                        detectHorizontalDragGestures(onDragStart = { valueAt(it.x, size.width.toFloat(), px) }) { change, _ ->
                            change.consume()
                            valueAt(change.position.x, size.width.toFloat(), px)
                        }
                    },
            ),
    ) {
        val px = m.u.toPx()
        drawSlider(slider.fraction, px, frame, paper, fill, knob, track)
    }
}

/** The knob's diameter and the track's height, in GBA pixels. */
private const val KNOB = 17
private const val TRACK = 9

private fun DrawScope.drawSlider(
    fraction: Float,
    px: Float,
    frame: List<Pair<androidx.compose.ui.graphics.Color, Int>>,
    paper: androidx.compose.ui.graphics.Color,
    fill: androidx.compose.ui.graphics.Color,
    knobUnits: Int,
    trackUnits: Int,
) {
    val knob = knobUnits * px
    val trackH = trackUnits * px
    val trackTop = ((size.height - trackH) / 2 / px).roundToInt() * px
    val inset = frame.sumOf { it.second } * px
    // The track spans the whole width, the knob's centre runs from one rounded end to the other.
    translate(0f, trackTop) {
        drawLayeredBoxSized(frame.inPx(px), paper, Size(size.width, trackH), radius = trackH / 2, step = px)
    }
    val travel = size.width - knob
    val knobLeft = ((travel * fraction.coerceIn(0f, 1f)) / px).roundToInt() * px
    val fillRight = knobLeft + knob / 2
    if (fillRight > inset) {
        drawPixelRoundRect(
            fill, Offset(inset, trackTop + inset), Size(fillRight - inset, trackH - 2 * inset),
            radius = (trackH - 2 * inset) / 2, step = px,
        )
    }
    translate(knobLeft, ((size.height - knob) / 2 / px).roundToInt() * px) {
        drawLayeredBoxSized(frame.inPx(px), paper, Size(knob, knob), radius = knob / 2, step = px)
    }
}

/** [drawLayeredBox] over [box] (from the current origin) rather than the whole canvas. */
private fun DrawScope.drawLayeredBoxSized(
    layers: List<Pair<androidx.compose.ui.graphics.Color, Float>>,
    fill: androidx.compose.ui.graphics.Color,
    box: Size,
    radius: Float,
    step: Float,
) {
    var inset = 0f
    var r = radius
    for ((color, width) in layers) {
        drawPixelRoundRect(color, Offset(inset, inset), Size(box.width - 2 * inset, box.height - 2 * inset), r.coerceAtLeast(0f), step)
        inset += width
        r -= width
    }
    drawPixelRoundRect(fill, Offset(inset, inset), Size(box.width - 2 * inset, box.height - 2 * inset), r.coerceAtLeast(0f), step)
}

/**
 * A slider setting in a pop-up, for easier control than the row's small bar (GAME VOLUME: a tap on its row): the title
 * with the value, a big bar between - and + buttons, and DONE. The bar takes the D-pad first (LEFT / RIGHT step it);
 * B / BACK or a tap outside closes it. [slider] is rebuilt by the caller on every change, so it shows the new value.
 */
@Composable
fun OptionSliderPopup(title: String, value: String, slider: SettingSlider, m: GbaTextMetrics, onDismiss: () -> Unit) {
    val u = m.u
    OptionOverlay(onDismiss, Modifier.widthIn(max = 560.dp)) {
        Column {
            OptionTitleWindow(title, m, trailing = value)
            Spacer(Modifier.height(u * 4))
            OptionListWindow(m, Modifier.fillMaxWidth()) {
                Column(Modifier.padding(horizontal = u * 8, vertical = u * 8), verticalArrangement = Arrangement.spacedBy(u * 8)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(u * 6)) {
                        val step = slider.step
                        OptionButton("-", m, onClick = { slider.set(slider.value - step) }, modifier = Modifier.size(m.rowHeight * 1.2f))
                        SliderStop(slider, m, Modifier.weight(1f))
                        OptionButton("+", m, onClick = { slider.set(slider.value + step) }, modifier = Modifier.size(m.rowHeight * 1.2f))
                    }
                    OptionButton(tk("DONE"), m, onClick = onDismiss, emphasis = true, modifier = Modifier.fillMaxWidth().height(m.rowHeight * 1.3f))
                }
            }
        }
    }
}

/** The pop-up's bar as a D-pad stop: ringed while focused, LEFT / RIGHT step it, the first thing focused. */
@Composable
private fun SliderStop(slider: SettingSlider, m: GbaTextMetrics, modifier: Modifier) {
    val self = remember { FocusRequester() }
    LocalOverlayStart.current?.register(self, current = true)
    var focused by remember { mutableStateOf(false) }
    Box(
        modifier
            .focusRequester(self)
            .onFocusChanged { focused = it.isFocused }
            .focusable()
            .onPreviewKeyEvent { slider.onKey(it) }
            .gbaFocusRing(focused, PixelRoundedShape(m.u * 3))
            .padding(m.u * 3),
    ) {
        GbaSlider(slider, m, enabled = true, modifier = Modifier.fillMaxWidth(), knob = 23, track = 13)
    }
}
