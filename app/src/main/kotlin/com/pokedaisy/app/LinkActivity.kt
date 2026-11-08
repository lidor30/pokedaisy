package com.pokedaisy.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Bundle
import android.util.Log
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.pokedaisy.app.companion.i18n.tk
import com.pokedaisy.app.companion.i18n.tr
import com.pokedaisy.app.companion.ui.AppBackdrop
import com.pokedaisy.app.companion.ui.GbaText
import com.pokedaisy.app.companion.ui.GbaTextMetrics
import com.pokedaisy.app.companion.ui.OptionButton
import com.pokedaisy.app.companion.ui.OptionColors
import com.pokedaisy.app.companion.ui.OptionConfirm
import com.pokedaisy.app.companion.ui.OptionListWindow
import com.pokedaisy.app.companion.ui.OptionRows
import com.pokedaisy.app.companion.ui.OptionSelector
import com.pokedaisy.app.companion.ui.OptionTitleWindow
import com.pokedaisy.app.companion.ui.rememberGbaTextMetrics
import com.pokedaisy.app.companion.ui.theme.AppColors
import com.pokedaisy.app.companion.ui.theme.AppTheme
import kotlinx.coroutines.delay
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.concurrent.thread

/**
 * LINK PLAY (a game's library menu): two to four GBA games running at once, joined by mGBA's own link cable
 * ([LinkCore]), for the games' cable club - trades (trade evolutions included), battles, Ruby / Sapphire / Emerald's
 * record mixing - between different games or two copies of one. Only the games: no companion, EXPAND, HUD or
 * hotkeys. A tap on a game (or L2 / R2 on a controller) gives it the buttons and its sound; the others keep running,
 * unheard. With a second screen the games are split between the two.
 *
 * Saves go the player's own way: each game's save is the one it plays ([SavesLocation.saveFor]), backed up first
 * ([SaveBackups]); a second copy of the same game plays a save of its own beside it, `<save> (link N)`. Leaving lets a
 * save in progress finish before the games close.
 */
class LinkActivity : ComponentActivity() {

    private enum class Phase { SETUP, STARTING, PLAYING, CLOSING }

    private class Game(val rom: File, val playable: File, val save: File)

    private lateinit var prefs: Prefs
    private val slots = mutableStateListOf<File>()
    private val labels = HashMap<String, String>()
    /** The slot being picked for (slots.size = a new one), with the GBA games to pick from. */
    private var picking by mutableStateOf<Pair<Int, List<File>>?>(null)
    private var phase by mutableStateOf(Phase.SETUP)
    private var notice by mutableStateOf<String?>(null)
    private var confirmLeave by mutableStateOf(false)
    private var hint by mutableStateOf(false)
    /** FF: every game runs up to [FF_SPEED]x, the focused one's sound off meanwhile (the FF button). */
    private var ffState by mutableStateOf(false)
    @Volatile private var ff = false

    private var games: List<Game> = emptyList()
    private var views: List<LinkGameView> = emptyList()
    private var mainCount = 0
    private val input = GbaInput()
    @Volatile private var focused = 0
    @Volatile private var looping = false
    private var loop: Thread? = null
    private var opened = false
    private var presentation: DualScreenPresentation? = null
    private var secondGrid: LinkGrid? = null
    private var ltDown = false
    private var rtDown = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        com.pokedaisy.app.companion.i18n.L10n.apply(prefs.appLanguage, null)
        input.setControls(GbaControls.load(getExternalFilesDir(null) ?: filesDir))
        intent?.getStringExtra(EXTRA_ROM)?.let { File(it) }?.takeIf { it.isFile }?.let { slots.add(it) }
        thread { slots.forEach { label(it) } }
        setContent { AppTheme { com.pokedaisy.app.companion.ui.CompactTextOnSmallWindows { Root() } } }
    }

    override fun onResume() {
        super.onResume()
        OptionColors.inGame = false
        if (phase == Phase.PLAYING) {
            showSecondScreen()
            startLoop()
        }
    }

    override fun onPause() {
        super.onPause()
        // The games wait while the app is away (HOME, the screen off): nothing runs unseen.
        stopLoop()
        input.releaseAll()
        presentation?.dismiss()
        presentation = null
    }

    override fun onDestroy() {
        super.onDestroy()
        stopLoop()
        presentation?.dismiss()
        presentation = null
        // Closed from the recents list mid-game: still let a save in progress finish.
        if (opened && phase != Phase.CLOSING) {
            opened = false
            thread(name = "pokedaisy-link-close") { LinkCore.pkLinkClose(CLOSE_FRAMES) }
        }
    }

    // --- setup ---

    private fun label(rom: File): String =
        labels[rom.absolutePath] ?: GameTitles.label(this, prefs, rom).also { labels[rom.absolutePath] = it }

    /** The GBA games the library holds (Game Boy and DS carts don't link with them). Blocking. */
    private fun gbaGames(): List<File> =
        RomFolder.libraryRoms(this, prefs).filter { !RomIdentity.isGameBoy(it) && !RomIdentity.isNds(it) }

    private fun pick(slot: Int) {
        thread {
            val list = gbaGames().onEach { label(it) }
            runOnUiThread {
                if (list.isEmpty()) notice = tr("No GBA games in the library yet")
                else picking = slot to list
            }
        }
    }

    /** What a slot shows: the game, and which save a second copy of it plays. */
    private fun slotValue(i: Int): String {
        val rom = slots[i]
        val copy = slots.take(i).count { it.absolutePath == rom.absolutePath }
        return if (copy == 0) label(rom) else tr("{0}, save {1}", label(rom), copy + 1)
    }

    /** [rom]'s save for its [copy]-th place in the link (0 = the game's own save). */
    private fun saveFor(rom: File, copy: Int): File {
        val own = SavesLocation.saveFor(this, prefs, rom)
        if (copy == 0) return own
        return File(own.parentFile, "${own.nameWithoutExtension} (link ${copy + 1}).${own.extension}")
    }

    private fun start() {
        if (slots.size < 2 || phase != Phase.SETUP) return
        phase = Phase.STARTING
        val picked = slots.toList()
        thread(name = "pokedaisy-link-open") {
            var refused: String? = null
            val list = picked.mapIndexed { i, rom ->
                val copy = picked.take(i).count { it.absolutePath == rom.absolutePath }
                val save = saveFor(rom, copy)
                SaveBackups.snapshot(save)
                RzipSave.unpackForPlay(save)?.let { refused = refused ?: it }   // RetroArch's compressed .srm
                Game(rom, RomArchive.playable(rom, cacheDir) ?: rom, save)
            }
            refused?.let { why ->
                runOnUiThread { phase = Phase.SETUP; notice = why }
                return@thread
            }
            val ok = LinkCore.pkLinkOpen(list.map { it.playable.absolutePath }.toTypedArray(), list.map { it.save.absolutePath }.toTypedArray())
            Log.i(TAG, "open ${list.size} games: $ok (${list.joinToString { it.rom.name }})")
            runOnUiThread {
                if (!ok) {
                    phase = Phase.SETUP
                    notice = tr("The games couldn't be started. Check that each one is a GBA game that opens on its own.")
                    return@runOnUiThread
                }
                opened = true
                games = list
                views = list.indices.map { i -> LinkGameView(this, i, LinkCore.pkLinkVideoWidth(i), LinkCore.pkLinkVideoHeight(i)) { focus(it) } }
                mainCount = if (secondScreen() != null) (list.size + 1) / 2 else list.size
                focused = 0
                views.forEachIndexed { i, v -> v.focused = i == 0 }
                phase = Phase.PLAYING
                hint = true
                showSecondScreen()
                startLoop()
            }
        }
    }

    // --- playing ---

    private fun secondScreen(): android.view.Display? = if (prefs.singleScreen) null else Screens.second(this)

    private fun showSecondScreen() {
        if (presentation != null || views.size <= mainCount) return
        val display = secondScreen() ?: return
        val grid = secondGrid ?: LinkGrid(this).also { secondGrid = it }
        grid.removeAllViews()
        views.drop(mainCount).forEach { (it.parent as? ViewGroup)?.removeView(it); grid.addView(it) }
        presentation = DualScreenPresentation(this, display) { grid }.also { runCatching { it.show() } }
    }

    private fun focus(i: Int) {
        if (i !in games.indices || i == focused) return
        input.releaseAll()
        focused = i
        views.forEachIndexed { k, v -> v.focused = k == i }
    }

    private fun cycle(step: Int) {
        if (games.isEmpty()) return
        focus(Math.floorMod(focused + step, games.size))
    }

    private fun startLoop() {
        if (looping || !opened) return
        looping = true
        loop = thread(name = "pokedaisy-link") { runLoop() }
    }

    private fun stopLoop() {
        looping = false
        loop?.join(2000)
        loop = null
    }

    private fun setFf(on: Boolean) {
        ffState = on
        ff = on
    }

    /** The link thread: a frame of every game, the focused one's keys and sound; its blocking audio writes pace it. With
     * FF on the frames are paced by the clock instead, [FF_SPEED] to a 60th of a second, and the track stays quiet. */
    private fun runLoop() {
        val rate = LinkCore.pkLinkSampleRate()
        val bytes = maxOf(AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT), rate / 15 * 4)
        val track = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_GAME).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
            .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(rate).setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).build())
            .setBufferSizeInBytes(bytes)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        track.play()
        val audio = ShortArray(8192)
        var heard = -1
        var frames = 0L
        var fast = false
        var next = System.nanoTime()
        // Once a second in the log: frames run, the link's own time per frame and the audio write's (what paced it).
        var statAt = System.nanoTime()
        var statFrames = 0
        var runNanos = 0L
        var writeNanos = 0L
        try {
            while (looping) {
                if (ff != fast) {
                    fast = ff
                    if (fast) { track.pause(); track.flush() } else track.play()
                    next = System.nanoTime()
                }
                val f = focused
                if (f != heard) {
                    for (i in games.indices) LinkCore.pkLinkSetKeys(i, 0)
                    LinkCore.pkLinkReadAudio(f, audio)   // what it made unheard
                    heard = f
                }
                LinkCore.pkLinkSetKeys(f, input.mask)
                val t0 = System.nanoTime()
                if (!LinkCore.pkLinkRunFrame()) {
                    Log.w(TAG, "link fault: ${LinkCore.pkLinkStatus()}")
                    runOnUiThread { notice = tr("The link stopped. Leave and start it again; your saves are safe.") }
                    break
                }
                val t1 = System.nanoTime()
                views.forEachIndexed { i, v -> v.publish { LinkCore.pkLinkCopyVideo(i, it) } }
                val n = LinkCore.pkLinkReadAudio(f, audio)
                if (fast) {
                    // Paced by the clock; behind (a slow frame), it doesn't race to catch up.
                    next += FF_FRAME_NANOS
                    val wait = next - System.nanoTime()
                    if (wait > 0) Thread.sleep(wait / 1_000_000, (wait % 1_000_000).toInt()) else next = System.nanoTime()
                } else if (n > 0) track.write(audio, 0, n * 2) else Thread.sleep(16)
                runNanos += t1 - t0
                writeNanos += System.nanoTime() - t1
                statFrames++
                if (System.nanoTime() - statAt >= 1_000_000_000L) {
                    Log.i(TAG, "fps=$statFrames run=${runNanos / statFrames / 1000}us rest=${writeNanos / statFrames / 1000}us audio=$n ff=$fast")
                    statAt = System.nanoTime(); statFrames = 0; runNanos = 0; writeNanos = 0
                }
                if (++frames % 1800 == 0L) Log.i(TAG, LinkCore.pkLinkStatus())
            }
        } finally {
            track.pause()
            track.flush()
            track.release()
        }
    }

    private fun leave() {
        confirmLeave = false
        stopLoop()
        presentation?.dismiss()
        presentation = null
        if (!opened) { finish(); return }
        phase = Phase.CLOSING
        opened = false
        thread(name = "pokedaisy-link-close") {
            LinkCore.pkLinkClose(CLOSE_FRAMES)
            runOnUiThread { finish() }
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (phase != Phase.PLAYING || confirmLeave) return super.dispatchKeyEvent(event)
        val down = event.action == KeyEvent.ACTION_DOWN
        when (event.keyCode) {
            KeyEvent.KEYCODE_BACK -> {
                if (!down) confirmLeave = true
                return true
            }
            KeyEvent.KEYCODE_BUTTON_L2, KeyEvent.KEYCODE_BUTTON_THUMBL -> {
                if (down && event.repeatCount == 0) cycle(-1)
                return true
            }
            KeyEvent.KEYCODE_BUTTON_R2, KeyEvent.KEYCODE_BUTTON_THUMBR -> {
                if (down && event.repeatCount == 0) cycle(1)
                return true
            }
        }
        if (down && event.repeatCount > 0) return input.buttonFor(event.keyCode) != null || super.dispatchKeyEvent(event)
        if (input.onKey(event.keyCode, down)) return true
        return super.dispatchKeyEvent(event)
    }

    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
        if (phase != Phase.PLAYING || confirmLeave || !event.isFromSource(InputDevice.SOURCE_JOYSTICK)) return super.dispatchGenericMotionEvent(event)
        // Analog L2 / R2 (Retroid's triggers send no keys): past half way, as their keys.
        val lt = maxOf(event.getAxisValue(MotionEvent.AXIS_LTRIGGER), event.getAxisValue(MotionEvent.AXIS_BRAKE))
        val rt = maxOf(event.getAxisValue(MotionEvent.AXIS_RTRIGGER), event.getAxisValue(MotionEvent.AXIS_GAS))
        if ((lt > 0.5f) != ltDown) { ltDown = lt > 0.5f; if (ltDown) cycle(-1) }
        if ((rt > 0.5f) != rtDown) { rtDown = rt > 0.5f; if (rtDown) cycle(1) }
        if (input.onMotion(event)) return true
        return super.dispatchGenericMotionEvent(event)
    }

    // --- screens ---

    @Composable
    private fun Root() {
        val m = rememberGbaTextMetrics()
        when (phase) {
            Phase.SETUP, Phase.STARTING -> Setup(m)
            Phase.PLAYING -> Playing(m)
            Phase.CLOSING -> Message(tr("Finishing the games' saves…"), m)
        }
        notice?.let { text ->
            OptionConfirm(tk("LINK PLAY"), text, tk("OK"), m, onConfirm = { notice = null }, onDismiss = { notice = null }, cancelLabel = null)
        }
    }

    @Composable
    private fun Setup(m: GbaTextMetrics) {
        BackHandler { finish() }
        Box(Modifier.fillMaxSize()) {
            AppBackdrop()
            // OptionRows scrolls by itself: the column around it doesn't (a scroll inside a scroll can't be measured).
            Column(Modifier.fillMaxSize().padding(16.dp)) {
                OptionTitleWindow(tk("LINK PLAY"), m, onBack = { finish() })
                Spacer(Modifier.height(m.u * 4))
                OptionListWindow(m, Modifier.fillMaxWidth().weight(1f, fill = false)) {
                    Column {
                        com.pokedaisy.app.companion.ui.LinkSlots(
                            slots.indices.map { i -> tr("GAME {0}", i + 1) to slotValue(i) }, canAdd = slots.size < LinkCore.MAX_GAMES, m,
                            onPick = { pick(it) }, onAdd = { pick(slots.size) }, modifier = Modifier.weight(1f, fill = false),
                        )
                        GbaText(
                            tr("Up to four GBA games run at once, joined as if by a link cable: trade, battle and mix records at the games' own Pokémon Centers. Tap a game to play it (L2 / R2 on a controller). The same game twice plays its second copy on a save of its own."),
                            OptionColors.label, OptionColors.labelShadow, rememberGbaTextMetrics(0.8f), maxLines = Int.MAX_VALUE,
                            modifier = Modifier.padding(m.u * 4),
                        )
                    }
                }
                Spacer(Modifier.height(m.u * 4))
                OptionButton(
                    if (phase == Phase.STARTING) tr("STARTING…") else tr("START LINK"), m, onClick = { start() },
                    modifier = Modifier.align(Alignment.End), emphasis = true, enabled = slots.size >= 2 && phase == Phase.SETUP,
                )
            }
            picking?.let { (slot, list) ->
                val remove = slot < slots.size
                val options: List<File?> = list + if (remove) listOf(null) else emptyList()
                OptionSelector(
                    tk("PICK A GAME"), options, slots.getOrNull(slot), { it?.let(::label) ?: tk("REMOVE") }, m,
                    onPick = { f ->
                        picking = null
                        when {
                            f == null -> slots.removeAt(slot)
                            slot < slots.size -> slots[slot] = f
                            else -> slots.add(f)
                        }
                    },
                    onDismiss = { picking = null },
                )
            }
        }
    }

    @Composable
    private fun Playing(m: GbaTextMetrics) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            AndroidView(
                factory = { ctx -> LinkGrid(ctx).apply { views.take(mainCount).forEach { (it.parent as? ViewGroup)?.removeView(it); addView(it) } } },
                modifier = Modifier.fillMaxSize(),
            )
            if (hint) {
                LaunchedEffect(Unit) { delay(6000); hint = false }
                Box(Modifier.align(Alignment.TopCenter).padding(top = 12.dp)) {
                    OptionListWindow(m) {
                        GbaText(
                            tr("Tap a game to play it, or L2 / R2. BACK leaves."), OptionColors.label, OptionColors.labelShadow, m,
                            modifier = Modifier.padding(horizontal = m.u * 6, vertical = m.u * 3),
                        )
                    }
                }
            }
            // FF: a toggle in the corner (lit red while on), over whichever game sits there.
            OptionButton(
                tk("FF"), m, onClick = { setFf(!ffState) }, emphasis = ffState,
                modifier = Modifier.align(Alignment.BottomEnd).padding(12.dp),
            )
            if (confirmLeave) {
                OptionConfirm(
                    tk("LEAVE LINK PLAY?"), tr("Save in each game first: what you did since a game's last save is lost."), tk("LEAVE"), m,
                    onConfirm = { leave() }, onDismiss = { confirmLeave = false },
                )
            }
        }
    }

    @Composable
    private fun Message(text: String, m: GbaTextMetrics) {
        BackHandler { }
        Box(Modifier.fillMaxSize()) {
            AppBackdrop()
            Column(Modifier.fillMaxSize().padding(16.dp)) {
                OptionTitleWindow(tk("LINK PLAY"), m)
                Spacer(Modifier.height(m.u * 4))
                OptionListWindow(m, Modifier.fillMaxWidth()) {
                    GbaText(text, OptionColors.label, OptionColors.labelShadow, m, maxLines = Int.MAX_VALUE, modifier = Modifier.padding(m.u * 4))
                }
            }
        }
    }

    companion object {
        const val EXTRA_ROM = "rom"
        private const val TAG = "pokedaisy/link"
        /** FF's speed: four frames of every game per 60th of a second. */
        private const val FF_SPEED = 4
        private const val FF_FRAME_NANOS = 1_000_000_000L / (60L * FF_SPEED)
        /** How long a save in progress may take to finish on leaving: a Gen 3 save takes ~280 frames. */
        private const val CLOSE_FRAMES = 900
    }
}

/** The games on one screen: one fills it, two side by side (or one over the other on a tall screen), three or four in
 * a 2 x 2 grid. */
class LinkGrid(context: Context) : ViewGroup(context) {
    init {
        setBackgroundColor(android.graphics.Color.BLACK)
    }

    private fun cells(w: Int, h: Int): Pair<Int, Int> = when {
        childCount <= 1 -> 1 to 1
        childCount == 2 -> if (w >= h) 2 to 1 else 1 to 2
        else -> 2 to 2
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = MeasureSpec.getSize(heightMeasureSpec)
        val (cols, rows) = cells(w, h)
        for (i in 0 until childCount) {
            getChildAt(i).measure(MeasureSpec.makeMeasureSpec(w / cols, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(h / rows, MeasureSpec.EXACTLY))
        }
        setMeasuredDimension(w, h)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val w = r - l
        val h = b - t
        val (cols, rows) = cells(w, h)
        for (i in 0 until childCount) {
            val x = (i % cols) * (w / cols)
            val y = (i / cols) * (h / rows)
            getChildAt(i).layout(x, y, x + w / cols, y + h / rows)
        }
    }
}

/** One game's picture: the largest whole scale that fits (a fraction only when even 1x doesn't), sharp pixels, a frame
 * round the focused one and a dim over the others. A tap focuses it. */
class LinkGameView(
    context: Context,
    private val index: Int,
    private val frameW: Int,
    private val frameH: Int,
    private val onTap: (Int) -> Unit,
) : View(context) {

    private val lock = Any()
    private val buffer: ByteBuffer = ByteBuffer.allocateDirect(frameW * frameH * 4).order(ByteOrder.nativeOrder())
    private val bitmap: Bitmap = Bitmap.createBitmap(frameW, frameH, Bitmap.Config.ARGB_8888)
    private val pixels = Paint().apply { isFilterBitmap = false }
    private val ring = Paint().apply { color = AppColors.focusRing.toArgb(); style = Paint.Style.STROKE }
    private val dim = Paint().apply { color = 0x73000000 }
    private val dst = Rect()

    var focused = false
        set(value) { field = value; postInvalidate() }

    /** The link thread hands over a frame: [copy] writes it into the buffer the next draw reads. */
    fun publish(copy: (ByteBuffer) -> Boolean) {
        synchronized(lock) {
            buffer.rewind()
            copy(buffer)
        }
        postInvalidateOnAnimation()
    }

    override fun onDraw(canvas: Canvas) {
        synchronized(lock) {
            buffer.rewind()
            bitmap.copyPixelsFromBuffer(buffer)
        }
        val pad = (6 * resources.displayMetrics.density).toInt()
        val room = minOf((width - 2 * pad).toFloat() / frameW, (height - 2 * pad).toFloat() / frameH)
        val scale = if (room >= 1f) kotlin.math.floor(room) else room
        val w = (frameW * scale).toInt()
        val h = (frameH * scale).toInt()
        dst.set((width - w) / 2, (height - h) / 2, (width + w) / 2, (height + h) / 2)
        canvas.drawBitmap(bitmap, null, dst, pixels)
        if (focused) {
            ring.strokeWidth = pad * 0.6f
            val o = ring.strokeWidth / 2 + pad * 0.2f
            canvas.drawRect(dst.left - o, dst.top - o, dst.right + o, dst.bottom + o, ring)
        } else {
            canvas.drawRect(dst, dim)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            onTap(index)
            performClick()
        }
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }
}
