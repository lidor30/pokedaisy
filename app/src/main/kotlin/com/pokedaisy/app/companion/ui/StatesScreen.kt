package com.pokedaisy.app.companion.ui

import com.pokedaisy.app.companion.i18n.tr
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.pokedaisy.app.companion.StateSlots
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Savestate slots for the bottom screen, in the SETTINGS tab's OPTION-screen
 * language (white title window, grey framed windows, grey/red pixel text)
 * straight over the game's backdrop - but as a grid of slot cards rather
 * than one list. Tap a slot's screenshot (or LOAD) to load it, SAVE to
 * overwrite; long-pressing a saved slot replaces its picture with DELETE, which
 * asks for a second tap before removing the slot. [tick] just forces a re-list
 * after a save/load.
 */
@Composable
fun StatesScreen(slots: StateSlots?, tick: Long) {
    val m = rememberGbaTextMetrics()
    val small = rememberGbaTextMetrics(textScale = 1f)
    if (slots == null) {
        Column {
            OptionTitleWindow(tr("SAVE STATES"), m)
            Spacer(Modifier.height(m.u * 4))
            OptionListWindow(m, Modifier.fillMaxWidth()) {
                GbaText(tr("NO GAME RUNNING"), OptionColors.label, OptionColors.labelShadow, m, Modifier.padding(m.u * 4))
            }
        }
        return
    }
    // Listing the slots stats ~50 files on shared storage: once on the way in, then off
    // the main thread whenever the sample ticks (every second while this tab is open).
    var list by remember(slots) { mutableStateOf(slots.list()) }
    val firstTick = remember(slots) { tick }
    LaunchedEffect(slots, tick) {
        if (tick != firstTick) list = withContext(Dispatchers.IO) { slots.list() }
    }
    // A SAVE / LOAD tap: re-list a few times over the next ~2 s rather than waiting for the
    // sample tick, so the new screenshot shows as soon as the emulator has written it.
    var requests by remember(slots) { mutableStateOf(0) }
    LaunchedEffect(slots, requests) {
        if (requests == 0) return@LaunchedEffect
        for (wait in longArrayOf(250, 500, 750, 1000)) {
            kotlinx.coroutines.delay(wait)
            list = withContext(Dispatchers.IO) { slots.list() }
        }
    }
    // One slot's delete confirmation at a time. The root tap handler below cancels it
    // from anywhere except the DELETE / CONFIRM DELETE? button itself.
    var deleting by remember(slots) { mutableStateOf<Int?>(null) }
    var confirming by remember(slots) { mutableStateOf(false) }
    Box(
        Modifier.fillMaxSize().pointerInput(deleting) {
            if (deleting != null) detectTapGestures {
                deleting = null
                confirming = false
            }
        },
    ) {
    Column(modifier = Modifier.fillMaxSize()) {
        OptionTitleWindow(tr("SAVE STATES"), m, trailing = tr("SLOT {0}", slots.currentIndex))
        Spacer(Modifier.height(m.u * 4))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(m.u * 4)) {
            OptionButton(tr("UNDO SAVE"), m, onClick = { slots.requestUndoSave(); requests++ }, enabled = deleting == null, rainbow = false, modifier = Modifier.weight(1f))
            OptionButton(tr("UNDO LOAD"), m, onClick = { slots.requestUndoLoad() }, enabled = deleting == null, rainbow = false, modifier = Modifier.weight(1f))
        }
        Spacer(Modifier.height(m.u * 4))
        BoxWithConstraints(Modifier.fillMaxSize()) {
            // A whole row of cards in view, SAVE / LOAD included: the thumbnails are 3:2 where that
            // fits and flatter where it doesn't (a 4:3 screen - the Retroid Pocket Duo Lite's 1280x960 -
            // cut the buttons off). The grid's columns, as GridCells.Adaptive works them out.
            val gap = m.u * 4
            val natural = ((maxWidth + gap) / (SLOT_MIN_WIDTH + gap)).toInt().coerceAtLeast(1)
            val cols = maxOf(MIN_SLOT_COLUMNS, natural)
            // Cards narrower than SAVE + LOAD need at the normal size: the buttons take the smaller text.
            val buttons = if (cols > natural) small else m
            val frame = m.u * (OptionColors.listLayers.sumOf { it.second } + 2) * 2
            val inner = (maxWidth - gap * (cols - 1)) / cols - frame
            val chrome = frame + m.lineHeight + m.u * 7 + small.lineHeight + maxOf(m.rowHeight, m.lineHeight + m.u * 8) + gap + m.u * 2
            val thumb = minOf(inner * 2f / 3f, maxHeight - chrome).coerceAtLeast(inner * 0.4f)
            LazyVerticalGrid(
                // Wide enough for SAVE and LOAD side by side (at 150 dp the wide screen fit six and cut them to "S…").
                columns = GridCells.Fixed(cols),
                horizontalArrangement = Arrangement.spacedBy(gap),
                verticalArrangement = Arrangement.spacedBy(gap),
                contentPadding = PaddingValues(bottom = gap),
                modifier = Modifier.fillMaxSize(),
            ) {
                items(list, key = { it.index }) { slot ->
                    SlotCard(
                        slot = slot,
                        isCurrent = slot.index == slots.currentIndex,
                        m = m, small = small, buttons = buttons, thumbHeight = thumb,
                        deleting = deleting == slot.index,
                        anyDeleting = deleting != null,
                        confirming = confirming,
                        onLongPress = { deleting = slot.index; confirming = false },
                        onDeleteTap = {
                            if (confirming) {
                                slots.requestDelete(slot.index)
                                requests++
                                deleting = null
                                confirming = false
                            } else confirming = true
                        },
                        onLoad = { slots.requestLoad(slot.index); requests++ },
                        onSave = { slots.requestSave(slot.index); requests++ },
                    )
                }
            }
        }
    }
    }
}

private val SLOT_MIN_WIDTH = 210.dp

/** At least this many cards a row (the Thor's bottom screen fit two at [SLOT_MIN_WIDTH]; the user wanted three). */
private const val MIN_SLOT_COLUMNS = 3

@Composable
private fun SlotCard(
    slot: StateSlots.Slot,
    isCurrent: Boolean,
    m: GbaTextMetrics,
    small: GbaTextMetrics,
    /** SAVE / LOAD's text: [m], or [small] on a narrow card. */
    buttons: GbaTextMetrics,
    thumbHeight: Dp,
    deleting: Boolean,
    anyDeleting: Boolean,
    confirming: Boolean,
    onLongPress: () -> Unit,
    onDeleteTap: () -> Unit,
    onLoad: () -> Unit,
    onSave: () -> Unit,
) {
    val u = m.u
    val clickSound = LocalClickSound.current
    // The slot the hotkeys target gets the OPTION list's white "cursor" fill.
    OptionListWindow(m, Modifier.fillMaxWidth(), fill = if (isCurrent) OptionColors.rowSelected else OptionColors.listFill) {
        Column {
            // Fixed height: every card's header is one line, cursor or not.
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.height(m.lineHeight).padding(horizontal = u * 2)) {
                GbaText(tr("SLOT {0}", slot.index), OptionColors.label, OptionColors.labelShadow, m, Modifier.weight(1f))
                if (isCurrent) {
                    PixelIcon(PixelIcons.cursorLeft, OptionColors.value, Modifier.width(m.lineHeight * 0.3f).height(m.lineHeight * 0.5f))
                }
            }
            Spacer(Modifier.height(u * 2))
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(thumbHeight)
                    .clip(PixelRoundedShape(u * 3))
                    .background(OptionColors.imageWell)
                    // Frame on top of the screenshot: the list window's dark + blue lines.
                    .drawWithContent {
                        drawContent()
                        val px = u.toPx()
                        drawLayeredFrame(
                            listOf(OptionColors.frameDark to px, OptionColors.frameLight to px),
                            radius = 3 * px,
                        )
                    },
            ) {
                if (deleting) {
                    OptionButton(
                        tr(if (confirming) "CONFIRM DELETE?" else "DELETE"),
                        buttons,
                        onClick = onDeleteTap,
                        emphasis = true,
                        rainbow = false,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = u * 2),
                    )
                } else {
                    val tap = if (anyDeleting) Modifier else Modifier.pointerInput(slot.index, slot.present) {
                        detectTapGestures(
                            // A normal tap keeps the upstream behaviour: load the slot.
                            onTap = {
                                if (slot.present) {
                                    clickSound()
                                    onLoad()
                                }
                            },
                            onLongPress = {
                                if (slot.present) onLongPress()
                            },
                        )
                    }
                    Box(
                        Modifier.matchParentSize().then(tap),
                        contentAlignment = Alignment.Center,
                    ) {
                    if (slot.present) {
                        val thumbPath = slot.thumbPath
                        // Keyed by the thumbnail's own write time, not the state's: a save writes
                        // the state, then the thumbnail, and a list read between the two paired the
                        // new save time with the old picture - cached, so the slot kept showing it.
                        // The time comes with the list (Slot.thumbModifiedMillis), not read here: an
                        // unchanged Slot skips this card's redraw, so a file read here went stale.
                        val thumbKey = thumbPath?.let { "$it@${slot.savedAtMillis}@${slot.thumbModifiedMillis}" }
                        val bmp by produceKeyedState(thumbKey?.let(StateThumbs::get), thumbKey) {
                            if (value != null || thumbPath == null || thumbKey == null) return@produceKeyedState
                            value = withContext(Dispatchers.IO) {
                                runCatching { BitmapFactory.decodeFile(thumbPath) }.getOrNull()
                            }?.also { StateThumbs.put(thumbKey, it) }
                        }
                        bmp?.let {
                            Image(
                                bitmap = it.asImageBitmap(),
                                contentDescription = tr("Slot {0}", slot.index),
                                modifier = Modifier.fillMaxSize(),
                                contentScale = ContentScale.Crop,
                                filterQuality = FilterQuality.None,
                            )
                        }
                    } else {
                        GbaText(tr("EMPTY"), OptionColors.muted, Color(0xFF101418), small)
                    }
                }
                }
            }
            Spacer(Modifier.height(u * 2))
            GbaText(
                if (slot.present) {
                    remember(slot.savedAtMillis) { slotDateFormat.format(Date(slot.savedAtMillis)) }
                } else "-",
                OptionColors.muted, OptionColors.mutedShadow, small,
                modifier = Modifier.padding(horizontal = u * 2),
            )
            Spacer(Modifier.height(u * 3))
            Row(horizontalArrangement = Arrangement.spacedBy(u * 3)) {
                // The D-pad starts on the slot the hotkeys target (the companion's window).
                OptionButton(tr("SAVE"), buttons, onClick = onSave, enabled = !anyDeleting, emphasis = true, rainbow = false, modifier = Modifier.weight(1f).focusStart(isCurrent))
                OptionButton(tr("LOAD"), buttons, onClick = onLoad, enabled = slot.present && !anyDeleting, rainbow = false, modifier = Modifier.weight(1f))
            }
        }
    }
}

private val slotDateFormat: DateFormat by lazy { DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT) }

/** Decoded slot thumbnails by path + save / thumbnail time, so a STATES visit doesn't decode all ten again. */
private object StateThumbs {
    private val map = object : LinkedHashMap<String, Bitmap>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Bitmap>?) = size > 16
    }
    fun get(key: String): Bitmap? = synchronized(map) { map[key] }
    fun put(key: String, bmp: Bitmap) = synchronized(map) { map[key] = bmp }
}
