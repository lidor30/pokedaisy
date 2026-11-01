package com.pokedaisy.app

import com.pokedaisy.app.companion.ui.produceKeyedState
import android.graphics.BitmapFactory
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.pokedaisy.app.companion.i18n.tk
import com.pokedaisy.app.companion.i18n.tr
import com.pokedaisy.app.companion.ui.AppBackdrop
import com.pokedaisy.app.companion.ui.GbaText
import com.pokedaisy.app.companion.ui.GbaTextMetrics
import com.pokedaisy.app.companion.ui.OptionColors
import com.pokedaisy.app.companion.ui.OptionLine
import com.pokedaisy.app.companion.ui.OptionListWindow
import com.pokedaisy.app.companion.ui.OptionTitleWindow
import com.pokedaisy.app.companion.ui.PixelPillShape
import com.pokedaisy.app.companion.ui.PixelRoundedShape
import com.pokedaisy.app.companion.ui.drawLayeredFrame
import com.pokedaisy.app.companion.ui.drawPixelRoundRect
import com.pokedaisy.app.companion.ui.inPx
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File

/**
 * The dual-screen launcher ([LibraryActivity] with a second screen): the game list on one screen and
 * everything about the game it's on - ScreenScraper's media ([GameMedia]) and facts - on the other.
 * X swaps the screens, animated: both screens' contents slide toward each other and fade out, trade
 * places, and come in from that same edge, so they look like they passed between the screens.
 *
 * One [LauncherState] serves both windows (the activity's and the second screen's Presentation): it's
 * Compose state, so either window redraws when the other changes it. Every key goes to the activity
 * (the Presentation forwards them), which moves [selected] itself rather than through Compose focus,
 * so the D-pad works whichever screen the list is on.
 */
class LauncherState {
    var games by mutableStateOf<List<File>>(emptyList())
        private set
    /** The quick views' pills over the games (LibraryView: ALL, FAVORITES, RECENT, the consoles and HACKS held)... */
    var views by mutableStateOf<List<LibraryView>>(emptyList())
    /** ...the open one ([games] are its), and LEFT / RIGHT's step to the next while on them ([viewCursor]). */
    var view by mutableStateOf(LibraryView.ALL)
    var onPickView: (LibraryView) -> Unit = {}
    /** The cursor in the games... */
    var selected by mutableIntStateOf(0)
    /** ...or on the quick views' row: UP off the games' top row lands there, UP again in the header. */
    var viewCursor by mutableStateOf(false)
    /** Games per row as last laid out: 1 in the list view, the grid's columns in the grid view. */
    var columns by mutableIntStateOf(1)
    /** The cursor up in the header, on this button (-1 = in the list): UP off the list's top lands on the first. Where
     * it was in the list is kept, for DOWN. */
    var headerCursor by mutableIntStateOf(-1)
    /** The header's buttons, as laid out. */
    var headerCount = 0
    /** The cursor on the GAMES / TOOLS / SETTINGS rail, on this place (-1 = not there): LEFT off the left edge lands there. */
    var navCursor by mutableIntStateOf(-1)
    /** The cursor on TOOLS' cards (while TOOLS is open in the games' place), and their columns as laid out. */
    var toolCursor by mutableIntStateOf(0)
    var toolColumns = 1
    /** The list on the second screen, the media on the main one. */
    var swapped by mutableStateOf(false)
    /** 0 at rest; 1 = both screens' contents fully out, mid-swap. */
    var transition by mutableFloatStateOf(0f)
        private set
    private var swapping = false
    /** B's options for the selected game, while open. */
    var menu by mutableStateOf<LauncherMenu?>(null)
    /** The list's scroll, kept when it moves to the other screen. */
    var scrollIndex = 0
    var scrollOffset = 0
    /** Bumped when a game's media lands on the device (re-reads the media screen). */
    var mediaGeneration by mutableIntStateOf(0)

    /** The game the cursor is on, or was on before it went up to the quick views / header (the media screen keeps it). */
    val current: File? get() = games.getOrNull(selected)

    /** A new list, keeping the cursor on its game where it can (else on [prefer], else where it was). [fresh]: another
     * quick view - the cursor starts on its first game (the list scrolls to its top, LauncherList). */
    fun setGames(list: List<File>, prefer: String?, fresh: Boolean = false) {
        val keep = games.getOrNull(selected)?.absolutePath ?: prefer
        games = list
        if (fresh) {
            selected = 0
            return
        }
        selected = list.indexOfFirst { it.absolutePath == keep }.takeIf { it >= 0 } ?: selected.coerceIn(0, (list.size - 1).coerceAtLeast(0))
    }

    /** UP / DOWN: a row (a grid row in the grid view); up off the games' top row onto the quick views, then the header. */
    fun moveRow(delta: Int) {
        if (headerCursor >= 0) {
            if (delta > 0) {
                headerCursor = -1
                viewCursor = views.isNotEmpty()
            }
            return
        }
        if (viewCursor) {
            if (delta < 0) headerCursor = 0
            if (delta < 0 || games.isNotEmpty()) viewCursor = false
            return
        }
        if (games.isEmpty()) {
            if (delta < 0) { if (views.isNotEmpty()) viewCursor = true else headerCursor = 0 }
            return
        }
        val next = selected + delta * columns
        when {
            next < 0 -> if (delta < 0) { if (views.isNotEmpty()) viewCursor = true else headerCursor = 0 }
            // Down into a last row that's short of this column: its last game.
            next > games.lastIndex -> if (selected / columns < games.lastIndex / columns) selected = games.lastIndex
            else -> selected = next
        }
    }

    /** LEFT / RIGHT: the next quick view while on them, along a grid row; in the list view, a page. */
    fun moveColumn(delta: Int) {
        when {
            headerCursor >= 0 -> headerCursor = (headerCursor + delta).coerceIn(0, (headerCount - 1).coerceAtLeast(0))
            viewCursor -> views.getOrNull(views.indexOf(view) + delta)?.let(onPickView)
            columns > 1 -> if (games.isNotEmpty()) selected = (selected + delta).coerceIn(0, games.lastIndex)
            else -> page(delta)
        }
    }

    /** L / R: [PAGE] rows at a time. */
    fun page(delta: Int) {
        if (games.isEmpty() || headerCursor >= 0 || viewCursor) return
        selected = (selected + delta * PAGE * columns).coerceIn(0, games.lastIndex)
    }

    /** A tap: the cursor onto [rom]. */
    fun select(rom: File) {
        headerCursor = -1
        viewCursor = false
        navCursor = -1
        games.indexOfFirst { it.absolutePath == rom.absolutePath }.takeIf { it >= 0 }?.let { selected = it }
    }

    /** Whether the cursor is on [rom]. */
    fun isCursor(rom: File): Boolean =
        headerCursor < 0 && !viewCursor && navCursor < 0 && games.getOrNull(selected)?.absolutePath == rom.absolutePath

    /** X: out (130 ms), trade screens, in (210 ms). Needs a frame clock: launch it from a composition's scope. */
    suspend fun swap(onSwapped: (Boolean) -> Unit) {
        if (swapping) return
        swapping = true
        try {
            animate(0f, 1f, animationSpec = tween(SWAP_OUT_MS, easing = FastOutLinearInEasing)) { v, _ -> transition = v }
            swapped = !swapped
            onSwapped(swapped)
            animate(1f, 0f, animationSpec = tween(SWAP_IN_MS, easing = LinearOutSlowInEasing)) { v, _ -> transition = v }
        } finally {
            transition = 0f
            swapping = false
        }
    }

    // ---- media, per game, off the UI thread and kept across visits ----------------

    private val cache = object : LinkedHashMap<String, LoadedMedia>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, LoadedMedia>?) = size > MEDIA_CACHE
    }

    @Synchronized fun cached(key: String): LoadedMedia? = cache[key]
    @Synchronized fun remember(key: String, m: LoadedMedia) { cache[key] = m }
    /** Media may have changed on disk: each game's files are looked at again (a changed one reloads). */
    fun mediaChanged() { mediaGeneration++ }

    companion object {
        /** Rows a page (L / R, and LEFT / RIGHT in the list view) moves. */
        const val PAGE = 6
        const val SWAP_OUT_MS = 130
        const val SWAP_IN_MS = 210
        /** Games whose decoded media are kept (a few MB each): going back and forth is instant. */
        private const val MEDIA_CACHE = 4
    }
}

/** A game's options (its library menu), opened with B; [cursor] moves with the D-pad. */
class LauncherMenu(val title: String, val items: List<Item>) {
    /** [opens]: it opens a window or a screen, and the menu comes back when that closes (B there returns here). */
    class Item(
        val label: String, val icon: androidx.compose.ui.graphics.vector.ImageVector? = null, val danger: Boolean = false, val opens: Boolean = false,
        /** A PixelIcons bitmap drawn instead of [icon] (the favorite stars). */
        val pixel: List<String>? = null,
        val onClick: () -> Unit,
    )
    var cursor by mutableIntStateOf(0)
    /** Rows per column: all of them, or half when the list is drawn in two columns (it didn't fit). */
    var rows by mutableIntStateOf(items.size)

    /** UP / DOWN: the next row, wrapping round. */
    fun move(delta: Int) {
        cursor = (cursor + delta).mod(items.size)
    }

    /** LEFT / RIGHT: the same row in the other column (with two). */
    fun moveColumn(delta: Int) {
        if (rows >= items.size) return
        cursor = (cursor + delta * rows).coerceIn(0, items.lastIndex)
    }
}

/** What the media screen draws for a game: ScreenScraper's files (null where it has none) and facts. */
class LoadedMedia(
    val box: ImageBitmap?,
    val screenshot: ImageBitmap?,
    val title: ImageBitmap?,
    val wheel: ImageBitmap?,
    val fanart: ImageBitmap?,
    val cart: ImageBitmap?,
    /** The library cover, for a game ScreenScraper has no box art for. */
    val cover: ImageBitmap?,
    val video: File?,
    val info: GameMedia.Info?,
) {
    companion object {
        /** Blocking: decodes [rom]'s media files, each scaled down (by halves) to about [maxSide] px. */
        fun load(root: File, rom: File, coverFile: File, maxSide: Int = 960): LoadedMedia {
            fun img(kind: GameMedia.Kind) = GameMedia.existing(root, rom, kind)?.let { decode(it, maxSide) }
            return LoadedMedia(
                box = img(GameMedia.Kind.BOX) ?: img(GameMedia.Kind.BOX_3D),
                screenshot = img(GameMedia.Kind.SCREENSHOT),
                title = img(GameMedia.Kind.TITLE),
                wheel = img(GameMedia.Kind.WHEEL),
                fanart = img(GameMedia.Kind.FANART),
                cart = img(GameMedia.Kind.CART),
                cover = coverFile.takeIf { it.isFile && it.length() > 0 }?.let { decode(it, maxSide) },
                video = GameMedia.existing(root, rom, GameMedia.Kind.VIDEO),
                info = GameMedia.info(root, rom)?.takeIf { it.found },
            )
        }

        private fun decode(f: File, maxSide: Int): ImageBitmap? = runCatching {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(f.absolutePath, bounds)
            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / sample > maxSide * 3 / 2) sample *= 2
            BitmapFactory.decodeFile(f.absolutePath, BitmapFactory.Options().apply { inSampleSize = sample })?.asImageBitmap()
        }.getOrNull()
    }
}

/** The local facts the media screen adds to ScreenScraper's (or shows alone without them). */
class LauncherFacts(val label: String, val lastPlayed: String?, val hasSave: Boolean, val signedIn: Boolean)

/**
 * The swap's motion on one screen: out toward the other screen (down on the main one - the top
 * screen - up on the second), faded and slightly shrunk; in from that same edge.
 */
fun Modifier.launcherSwapMotion(state: LauncherState, onMain: Boolean): Modifier = graphicsLayer {
    val t = state.transition
    translationY = (if (onMain) 1f else -1f) * t * size.height * 0.3f
    alpha = 1f - t
    val s = 1f - 0.05f * t
    scaleX = s
    scaleY = s
}

/**
 * The media screen: the game's fanart (or screenshot) behind everything, its logo on top, box art,
 * the screenshot (the video, once the game has been on for a moment) and its facts, and its story.
 * [video] plays a video file in a frame (the Android host's; null = screenshots only).
 */
@Composable
fun LauncherMediaPane(
    state: LauncherState,
    mediaRoot: File,
    coverFor: (File) -> File,
    factsFor: (File) -> LauncherFacts,
    m: GbaTextMetrics,
    small: GbaTextMetrics,
    video: (@Composable (File, Modifier) -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val rom = state.current
    Box(modifier.fillMaxSize().background(OptionColors.imageWell)) {
        Crossfade(targetState = rom, animationSpec = tween(180), label = "launcher-media") { game ->
            if (game == null) {
                Box(Modifier.fillMaxSize()) { AppBackdrop() }
                return@Crossfade
            }
            val generation = state.mediaGeneration
            // Keyed on the files' times: a fetch that landed reloads, a mere revisit is a cache hit.
            val key = remember(game, generation) {
                "${game.absolutePath}#${GameMedia.dir(mediaRoot, game).lastModified()}#${coverFor(game).lastModified()}"
            }
            val media by produceKeyedState(state.cached(key), key) {
                if (value == null) {
                    value = withContext(Dispatchers.IO) {
                        LoadedMedia.load(mediaRoot, game, coverFor(game)).also { state.remember(key, it) }
                    }
                }
            }
            val facts = remember(game, generation) { factsFor(game) }
            MediaPage(media, facts, m, small, video)
        }
    }
}

@Composable
private fun MediaPage(
    media: LoadedMedia?,
    facts: LauncherFacts,
    m: GbaTextMetrics,
    small: GbaTextMetrics,
    video: (@Composable (File, Modifier) -> Unit)?,
) {
    Box(Modifier.fillMaxSize()) {
        // The backdrop: fanart (painted art, smoothed) or the screenshot (pixel art, kept sharp), darkened.
        val back = media?.fanart ?: media?.screenshot
        if (back != null) {
            Image(
                back, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop,
                filterQuality = if (media?.fanart != null) FilterQuality.Low else FilterQuality.None,
            )
            Box(
                Modifier.fillMaxSize().background(
                    Brush.verticalGradient(listOf(Color(0x66000000), Color(0xB3000000), Color(0xE6000000))),
                ),
            )
        } else {
            AppBackdrop()
        }
        BoxWithConstraints(Modifier.fillMaxSize().padding(16.dp)) {
            val h = maxHeight
            val wide = maxWidth / h >= 1.45f
            val logoHeight = (h * if (wide) 0.2f else 0.17f).coerceAtMost(140.dp)
            val gap = m.u * 4
            if (wide) {
                // A wide screen (the top one): box art down the left, the rest beside it.
                Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(m.u * 6)) {
                    BoxArt(media, m, Modifier.fillMaxHeight().weight(0.4f))
                    Column(Modifier.weight(0.6f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(gap)) {
                        Logo(media, facts, m, Modifier.fillMaxWidth().height(logoHeight))
                        Row(Modifier.fillMaxWidth().weight(0.55f), horizontalArrangement = Arrangement.spacedBy(gap)) {
                            Screen(media, m, video, Modifier.weight(1f).fillMaxHeight())
                            FactsWindow(media?.info, facts, m, small, Modifier.weight(1f).fillMaxHeight())
                        }
                        Story(media?.info, facts, m, small, Modifier.fillMaxWidth().weight(0.45f))
                    }
                }
            } else {
                // Squarer (the Thor's bottom screen): logo, box art beside the facts, the screen beside the story.
                Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(gap)) {
                    Logo(media, facts, m, Modifier.fillMaxWidth().height(logoHeight))
                    Row(Modifier.fillMaxWidth().weight(0.56f), horizontalArrangement = Arrangement.spacedBy(gap)) {
                        BoxArt(media, m, Modifier.fillMaxHeight().weight(0.42f))
                        FactsWindow(media?.info, facts, m, small, Modifier.weight(0.58f).fillMaxHeight())
                    }
                    Row(Modifier.fillMaxWidth().weight(0.44f), horizontalArrangement = Arrangement.spacedBy(gap)) {
                        Screen(media, m, video, Modifier.fillMaxHeight())
                        Story(media?.info, facts, m, small, Modifier.weight(1f).fillMaxHeight())
                    }
                }
            }
        }
    }
}

/** The game's wheel logo, else its name in the title window. */
@Composable
private fun Logo(media: LoadedMedia?, facts: LauncherFacts, m: GbaTextMetrics, modifier: Modifier) {
    val wheel = media?.wheel
    if (wheel != null) {
        Image(wheel, facts.label, modifier, contentScale = ContentScale.Fit, filterQuality = FilterQuality.Low)
    } else {
        Box(modifier, contentAlignment = Alignment.Center) {
            OptionTitleWindow(media?.info?.name ?: facts.label, m)
        }
    }
}

/** Box art (or the library cover), whole, in a frame; a drawn cartridge without either. */
@Composable
private fun BoxArt(media: LoadedMedia?, m: GbaTextMetrics, modifier: Modifier) {
    val art = media?.box ?: media?.cover ?: media?.cart
    Box(modifier, contentAlignment = Alignment.Center) {
        if (art != null) {
            val ratio = art.width.toFloat() / art.height.coerceAtLeast(1)
            Image(
                art, null,
                Modifier.aspectRatio(ratio).framed(m),
                contentScale = ContentScale.Fit,
                filterQuality = if (art.width <= 256) FilterQuality.None else FilterQuality.Low,
            )
        } else {
            Box(
                Modifier.fillMaxWidth(0.7f).aspectRatio(1f).framed(m).background(OptionColors.imageWell),
                contentAlignment = Alignment.Center,
            ) {
                GbaText("GBA", OptionColors.muted, OptionColors.mutedShadow, m)
            }
        }
    }
}

/** The screenshot (GBA 3:2), the video in its place once a game has been on screen for a moment. */
@Composable
private fun Screen(media: LoadedMedia?, m: GbaTextMetrics, video: (@Composable (File, Modifier) -> Unit)?, modifier: Modifier) {
    val shot = media?.screenshot ?: media?.title
    val clip = media?.video?.takeIf { video != null }
    var playing by remember(clip) { mutableStateOf(false) }
    if (clip != null) {
        LaunchedEffect(clip) {
            delay(VIDEO_DELAY_MS)
            playing = true
        }
    }
    if (shot == null && clip == null) return
    Box(modifier, contentAlignment = Alignment.Center) {
      Box(Modifier.aspectRatio(1.5f).framed(m).background(Color.Black)) {
        if (shot != null) {
            Image(shot, null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit, filterQuality = FilterQuality.None)
        }
        if (clip != null && playing) video!!(clip, Modifier.fillMaxSize())
      }
    }
}

/** A game on screen this long starts its video, so scrolling past games doesn't start each one. */
private const val VIDEO_DELAY_MS = 1200L

/** RELEASED / DEVELOPER / PUBLISHER / GENRE / PLAYERS / RATING, then the library's own LAST PLAYED and SAVE. */
@Composable
private fun FactsWindow(info: GameMedia.Info?, facts: LauncherFacts, m: GbaTextMetrics, small: GbaTextMetrics, modifier: Modifier) {
    val rows = buildList {
        info?.released?.let { add(tk("RELEASED") to it) }
        info?.developer?.let { add(tk("DEVELOPER") to it) }
        info?.publisher?.let { add(tk("PUBLISHER") to it) }
        info?.genre?.let { add(tk("GENRE") to it) }
        info?.players?.let { add(tk("PLAYERS") to it) }
        info?.rating?.let { r -> add(tk("RATING") to (r.toIntOrNull()?.let { "$it / 20" } ?: r)) }
        add(tk("LAST PLAYED") to (facts.lastPlayed ?: tk("NEVER")))
        add(tk("SAVE") to if (facts.hasSave) tk("YES") else tk("NONE"))
    }
    OptionListWindow(m, modifier) {
        WholeRows(Modifier.fillMaxSize().padding(horizontal = m.u * 4, vertical = m.u * 2)) {
            rows.forEach { (label, value) ->
                Row(Modifier.fillMaxWidth().padding(vertical = small.u), verticalAlignment = Alignment.CenterVertically) {
                    GbaText(tr(label), OptionColors.muted, OptionColors.mutedShadow, small, Modifier.weight(0.46f))
                    GbaText(value, OptionColors.value, OptionColors.valueShadow, small, Modifier.weight(0.54f))
                }
            }
        }
    }
}

/** A column of the rows that fit whole, centred: a row with no room left is dropped (from the end: the library's own facts) rather than cut in half. */
@Composable
private fun WholeRows(modifier: Modifier, content: @Composable () -> Unit) {
    androidx.compose.ui.layout.Layout(content, modifier) { measurables, constraints ->
        val loose = constraints.copy(minHeight = 0, maxHeight = Constraints.Infinity)
        val placeables = measurables.map { it.measure(loose) }
        val max = if (constraints.hasBoundedHeight) constraints.maxHeight else Int.MAX_VALUE
        var used = 0
        val shown = placeables.takeWhile { p -> (used + p.height <= max).also { if (it) used += p.height } }
        val height = if (constraints.hasBoundedHeight) constraints.maxHeight else used
        layout(constraints.maxWidth, height) {
            var y = (height - used) / 2
            shown.forEach { it.place(0, y); y += it.height }
        }
    }
}

/** The game's story; without ScreenScraper, how to get it (nothing while ScreenScraper is off: FeatureFlags). */
@Composable
private fun Story(info: GameMedia.Info?, facts: LauncherFacts, m: GbaTextMetrics, small: GbaTextMetrics, modifier: Modifier) {
    val text = info?.synopsis
        ?: if (!ScreenScraperClient.enabled) return
        else if (!facts.signedIn) tr("Sign in to ScreenScraper in SETTINGS for this game's box art, screenshots, video and story.")
        else if (info == null) tr("ScreenScraper has nothing for this game yet.")
        else return
    OptionListWindow(m, modifier) {
        AutoScroll(Modifier.fillMaxSize().padding(horizontal = m.u * 4, vertical = m.u * 3)) {
            GbaText(text, OptionColors.label, OptionColors.labelShadow, small, maxLines = Int.MAX_VALUE)
        }
    }
}

/**
 * [content] at its full height, clipped to this box; when it's taller, it scrolls down slowly and
 * back (a pause at each end), like a launcher's description. An infinite transition, not a delay loop
 * (the preview's test clock never idles on one).
 */
@Composable
private fun AutoScroll(modifier: Modifier, content: @Composable () -> Unit) {
    var overflow by remember { mutableIntStateOf(0) }
    val fraction = if (overflow > 0) {
        val t = rememberInfiniteTransition(label = "story-scroll")
        val cycle = (STORY_PAUSE_MS * 2 + overflow * STORY_MS_PER_PX).toInt()
        t.animateFloat(
            0f, 1f,
            infiniteRepeatable(
                keyframes {
                    durationMillis = cycle
                    0f at STORY_PAUSE_MS.toInt()
                    1f at cycle - STORY_PAUSE_MS.toInt()
                },
                RepeatMode.Reverse,
            ),
            label = "story-scroll-fraction",
        ).value
    } else {
        0f
    }
    Box(
        modifier
            // While it scrolls, the cut edges fade out (a line cut in half looked like a glitch).
            .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
            .drawWithContent {
                drawContent()
                if (overflow > 0) {
                    val fade = 24.dp.toPx().coerceAtMost(size.height / 4)
                    // Only the edge with more text past it: the first line stays whole at the top.
                    if (fraction > 0f) drawRect(Brush.verticalGradient(0f to Color.Transparent, fade / size.height to Color.Black), blendMode = BlendMode.DstIn)
                    if (fraction < 1f) drawRect(Brush.verticalGradient(1f - fade / size.height to Color.Black, 1f to Color.Transparent), blendMode = BlendMode.DstIn)
                }
            }
            .clipToBounds().layout { measurable, constraints ->
            val p = measurable.measure(constraints.copy(minHeight = 0, maxHeight = Constraints.Infinity))
            val h = if (constraints.hasBoundedHeight) constraints.maxHeight else p.height
            overflow = (p.height - h).coerceAtLeast(0)
            layout(p.width, h) { p.place(0, -(overflow * fraction).toInt()) }
        },
    ) { content() }
}

private const val STORY_PAUSE_MS = 3000L
/** Scroll speed: about 25 px a second at the Thor's density. */
private const val STORY_MS_PER_PX = 40L

/** The library's image frame (dark outer line, lilac inner), at a 3u pixel corner. */
private fun Modifier.framed(m: GbaTextMetrics): Modifier {
    val u = m.u
    return this.clip(PixelRoundedShape(u * 3)).drawWithContent {
        drawContent()
        val px = u.toPx()
        drawLayeredFrame(listOf(OptionColors.frameDark to px, OptionColors.frameLight to px), radius = 3 * px)
    }
}

/**
 * B's options for the selected game, over the list's screen: the OPTION pick-list, its cursor the
 * white row (moved by the D-pad, A picks, B closes) - taps work too. As wide as its longest entry
 * (each with the classic menu's icon), over a light scrim: the list stays readable behind it.
 */
@Composable
fun LauncherMenuOverlay(menu: LauncherMenu, m: GbaTextMetrics, onDismiss: () -> Unit) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        // Two columns (down the first, then the second) when one runs past the screen's height.
        val chrome = m.rowHeight * 2 + m.u * 20 + 32.dp
        // A little room in each entry: taller rows.
        val rowHeight = m.rowHeight * 1.2f
        val rows = if (rowHeight * menu.items.size + chrome <= maxHeight) menu.items.size else (menu.items.size + 1) / 2
        LaunchedEffect(rows) { menu.rows = rows }
        val noRipple = remember { MutableInteractionSource() }
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.fillMaxSize()
                .background(MENU_SCRIM)
                .clickable(interactionSource = noRipple, indication = null, onClick = onDismiss)
                .padding(16.dp),
        ) {
            Column(
                Modifier.width(IntrinsicSize.Max).clickable(interactionSource = noRipple, indication = null, onClick = {}),
            ) {
                OptionTitleWindow(menu.title, m, Modifier.fillMaxWidth())
                Spacer(Modifier.height(m.u * 4))
                OptionListWindow(m, Modifier.fillMaxWidth()) {
                    Row(Modifier.height(IntrinsicSize.Min)) {
                        menu.items.chunked(rows).forEachIndexed { c, column ->
                            Column(Modifier.width(IntrinsicSize.Max)) {
                                column.forEachIndexed { r, item ->
                                    val i = c * rows + r
                                    OptionLine(
                                        item.label, null, selected = i == menu.cursor, m,
                                        height = rowHeight,
                                        labelWeight = 1f,
                                        divider = r < column.lastIndex,
                                        labelIcon = if (item.pixel != null || item.icon != null) {
                                            {
                                                val tint = if (item.danger) OptionColors.value else OptionColors.label
                                                val px = item.pixel
                                                if (px != null) com.pokedaisy.app.companion.ui.PixelIcon(px, tint, Modifier.fillMaxSize())
                                                else Icon(item.icon!!, null, Modifier.fillMaxSize(), tint = tint)
                                            }
                                        } else null,
                                    ) {
                                        onDismiss()
                                        item.onClick()
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** The menu's scrim: the list dimmed, still readable (the OPTION overlays' near-black hid the whole screen). */
private val MENU_SCRIM = Color(0x73000000)

/** One button of the legend: the face button's letter and what it does here. */
class LegendEntry(val button: String, val label: String, val onClick: () -> Unit)

/**
 * The floating legend, bottom right of the list's screen: A / B / X / Y and what each does. Each entry
 * is a button too, for touch. A stepped glow and a drop shadow lift it off the rows scrolling under it.
 */
@Composable
fun ButtonLegend(entries: List<LegendEntry>, m: GbaTextMetrics, modifier: Modifier = Modifier) {
    val u = m.u
    Row(
        modifier
            .wrapContentSize()
            .drawBehind {
                val px = u.toPx()
                // The glow: rings a GBA pixel apart, fading out (whole pixels and pixel corners, like the frame).
                for (i in LEGEND_GLOW downTo 1) {
                    val grow = i * px
                    drawPixelRoundRect(
                        Color.Black.copy(alpha = 0.07f), Offset(-grow, -grow), Size(size.width + 2 * grow, size.height + 2 * grow),
                        radius = 3 * px + grow, step = px,
                    )
                }
                // The drop shadow: the window's own shape, 2 pixels down.
                drawPixelRoundRect(Color.Black.copy(alpha = 0.25f), Offset(0f, 2 * px), size, radius = 3 * px, step = px)
            }
            .clip(PixelRoundedShape(u * 3))
            .background(OptionColors.titleFill.copy(alpha = 0.94f))
            .drawWithContent {
                drawContent()
                val px = u.toPx()
                drawLayeredFrame(OptionColors.titleLayers.inPx(px), radius = 3 * px)
            }
            .padding(horizontal = u * 5, vertical = u * 3),
        horizontalArrangement = Arrangement.spacedBy(u * 6),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val noRipple = remember { MutableInteractionSource() }
        entries.forEach { e ->
            Row(
                Modifier.clickable(interactionSource = noRipple, indication = null, onClick = e.onClick),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                FaceButton(e.button, m, m.lineHeight + u * 2)
                Spacer(Modifier.width(u * 3))
                GbaText(e.label, OptionColors.label, OptionColors.labelShadow, m)
            }
        }
    }
}

/** The legend's glow, in GBA pixels past its frame. */
private const val LEGEND_GLOW = 4

/** A round face button with its letter, in the colours it has on most pads' legends. */
@Composable
private fun FaceButton(letter: String, m: GbaTextMetrics, size: Dp) {
    val fill = when (letter) {
        "A" -> Color(0xFFD83A3A)
        "B" -> Color(0xFFE8B020)
        "X" -> Color(0xFF3A6AD8)
        "Y" -> Color(0xFF30A050)
        else -> OptionColors.muted
    }
    Box(
        Modifier.size(size).clip(PixelPillShape).background(fill),
        contentAlignment = Alignment.Center,
    ) {
        GbaText(letter, Color.White, Color(0x80000000), m)
    }
}
