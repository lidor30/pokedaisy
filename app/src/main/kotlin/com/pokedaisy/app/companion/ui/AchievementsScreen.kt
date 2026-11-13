package com.pokedaisy.app.companion.ui

import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.MutableInteractionSource
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.pokedaisy.app.companion.Achievement
import com.pokedaisy.app.companion.AchievementGame
import com.pokedaisy.app.companion.AchievementPopup
import com.pokedaisy.app.companion.AchievementsState
import com.pokedaisy.app.companion.CompanionAchievements
import com.pokedaisy.app.companion.Leaderboard
import com.pokedaisy.app.companion.LeaderboardEntry
import com.pokedaisy.app.companion.LeaderboardPage
import com.pokedaisy.app.companion.i18n.tk
import com.pokedaisy.app.companion.i18n.tr
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap

/**
 * Achievement badges (64x64 PNGs on RetroAchievements' media server), fetched
 * once into [dir] and decoded once. [dir] is set by the game's activity; null
 * (screenshot tests) = no downloads, every badge draws [PixelIcons.trophy].
 */
object AchievementBadges {
    @Volatile var dir: File? = null
    private val cache = ConcurrentHashMap<String, Bitmap>()

    fun cached(url: String): Bitmap? = cache[url]

    /** Blocking (IO thread): the badge at [url], from disk or the network, or null. */
    fun load(url: String): Bitmap? {
        cache[url]?.let { return it }
        val d = dir ?: return null
        val name = url.substringAfterLast('/').filter { it.isLetterOrDigit() || it == '_' || it == '.' }
        if (name.isEmpty()) return null
        val f = File(d, name)
        if (!f.isFile) runCatching {
            d.mkdirs()
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 10000
                readTimeout = 15000
            }
            val bytes = conn.inputStream.use { it.readBytes() }
            conn.disconnect()
            val tmp = File(d, "$name.part")
            tmp.writeBytes(bytes)
            tmp.renameTo(f)
        }
        val bmp = runCatching { BitmapFactory.decodeFile(f.path) }.getOrNull() ?: return null
        cache[url] = bmp
        return bmp
    }
}

/** A badge in the list window's dark + blue frame; [url] null or not loaded = the trophy. */
@Composable
fun AchievementBadge(url: String?, size: Dp, modifier: Modifier = Modifier, dim: Boolean = false) {
    val m = rememberGbaTextMetrics()
    val u = m.u
    val bmp by produceKeyedState(url?.let { AchievementBadges.cached(it) }, url) {
        if (value == null && url != null) value = withContext(Dispatchers.IO) { AchievementBadges.load(url) }
    }
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .size(size)
            .clip(PixelRoundedShape(u * 2))
            .background(OptionColors.imageWell)
            .drawWithContent {
                drawContent()
                val px = u.toPx()
                drawLayeredFrame(listOf(OptionColors.frameDark to px, OptionColors.frameLight to px), radius = 2 * px)
            },
    ) {
        val b = bmp
        if (b != null) {
            Image(
                b.asImageBitmap(), contentDescription = null, contentScale = ContentScale.Crop,
                filterQuality = FilterQuality.None, modifier = Modifier.fillMaxSize().padding(u * 2),
            )
        } else {
            PixelArt(
                PixelIcons.trophy,
                if (dim) mapOf('1' to OptionColors.muted, '2' to OptionColors.label)
                else mapOf('1' to Color(0xFFF8C800), '2' to Color(0xFFB07000)),
                Modifier.fillMaxSize().padding(size / 5),
            )
        }
    }
}

/** Which achievements the list shows (its filter button, a pick-list). */
enum class AchievementFilter(val label: String, val short: String) {
    ALL("SHOW ALL", "ALL"),
    LOCKED("LOCKED ONLY", "LOCKED"),
    UNLOCKED("UNLOCKED ONLY", "UNLOCKED");

    fun shows(a: Achievement) = when (this) {
        ALL -> true
        LOCKED -> !a.unlocked
        UNLOCKED -> a.unlocked
    }
}

/** A request to open the list on achievement [id] (a tapped unlock / progress popup), marked as the
 * cursor row; [unlocked] = it's an unlock, so a LOCKED filter would hide it. [id] 0 = just the list. */
class AchievementFocus(val id: Int, val unlocked: Boolean) {
    val at = System.nanoTime()
}

/**
 * The ACHIEVEMENTS tab: the running game's RetroAchievements set in the
 * OPTION look - a title window with the tally, then one list window, grouped
 * the way rc_client groups them (recently unlocked, almost there, locked,
 * unlocked). Signed out / offline / no set for this ROM each get a message.
 */
@Composable
fun AchievementsScreen(
    achievements: CompanionAchievements?,
    modifier: Modifier = Modifier,
    /** Opens on the leaderboards, or on leaderboard [initialBoard]'s page - for screenshot tests. */
    initialLeaderboards: Boolean = false,
    initialBoard: Int? = null,
    /** Open on this achievement (a tapped popup); [onFocused] once taken, so a later visit doesn't repeat it. */
    focus: AchievementFocus? = null,
    onFocused: () -> Unit = {},
    /** The filter shown first, and its pick-list open - for screenshot tests. */
    initialFilter: AchievementFilter = AchievementFilter.ALL,
    initialPicking: Boolean = false,
) {
    val m = rememberGbaTextMetrics()
    val small = rememberGbaTextMetrics(1f)
    val state = achievements?.state?.collectAsState()?.value ?: AchievementsState()
    val game = state.game
    var filter by remember { mutableStateOf(initialFilter) }
    var picking by remember { mutableStateOf(initialPicking) }
    var showBoards by remember { mutableStateOf(initialLeaderboards) }
    var openBoard by remember { mutableStateOf(initialBoard) }
    // The achievement a popup opened, marked as the cursor row until the tab is left.
    var marked by remember { mutableStateOf<AchievementFocus?>(null) }
    val listState = rememberLazyListState()
    LaunchedEffect(focus) {
        val f = focus ?: return@LaunchedEffect
        showBoards = false
        if (openBoard != null) {
            achievements?.closeLeaderboard()
            openBoard = null
        }
        if (f.id != 0) {
            // Don't let the filter hide what was asked for.
            if (filter == (if (f.unlocked) AchievementFilter.LOCKED else AchievementFilter.UNLOCKED)) filter = AchievementFilter.ALL
            marked = f
        }
        onFocused()
    }
    val hasList = state.user != null && game != null && game.id != 0 && state.achievements.isNotEmpty()
    val board = openBoard?.let { id -> state.leaderboards.firstOrNull { it.id == id } }
    if (hasList && board != null) {
        val page = achievements?.leaderboardPage?.collectAsState()?.value
        LeaderboardPageView(board, page?.takeIf { it.id == board.id }, m, small, modifier) {
            achievements?.closeLeaderboard()
            openBoard = null
        }
        return
    }
    Column(modifier.fillMaxSize()) {
        val boardsShown = hasList && showBoards && state.leaderboards.isNotEmpty()
        OptionTitleWindow(
            if (boardsShown) tk("LEADERBOARDS") else tk("ACHIEVEMENTS"), m,
            trailing = when {
                boardsShown -> tr("{0} BOARDS", state.leaderboards.size)
                else -> game?.takeIf { hasList }?.let { "${it.unlocked}/${it.total} · " + tr("{0}/{1} PTS", it.pointsUnlocked, it.points) }
            },
        )
        Spacer(Modifier.height(m.u * 4))
        when {
            state.user == null -> Message(
                tr("NOT SIGNED IN"),
                tr("Sign in to RetroAchievements in SETTINGS on the top screen (RetroAchievements). Achievements then unlock as you play and show up here."),
                m, small,
            )
            state.offline && game == null -> Message(
                tr("CAN'T REACH RetroAchievements"),
                tr("Signed in as {0}, but the server can't be reached right now. It's tried again by itself every few minutes.", state.user),
                m, small,
            ) { OptionButton(tk("TRY AGAIN"), m, onClick = { achievements?.retry() }, emphasis = true) }
            game == null -> Message(tr("LOOKING UP THIS ROM"), tr("Asking RetroAchievements which game this is..."), m, small, loading = true)
            game.id == 0 -> Message(
                tr("NO ACHIEVEMENTS FOR THIS ROM"),
                tr("RetroAchievements matches a ROM by its exact hash. Retail games and some popular hacks have sets; a patched or rebuilt ROM usually doesn't."),
                m, small, detail = game.hash?.let { tr("HASH {0}", it.uppercase()) },
            )
            state.achievements.isEmpty() -> Message(tr("LOADING ACHIEVEMENTS"), "${game.title.orEmpty()}", m, small, loading = true)
            showBoards && state.leaderboards.isNotEmpty() -> LeaderboardList(
                state.leaderboards, game, m, small, Modifier.weight(1f),
                onAchievements = { showBoards = false },
                onOpen = { id -> openBoard = id; achievements?.openLeaderboard(id) },
            )
            else -> AchievementList(
                state.achievements, game, filter, { picking = true }, m, small, Modifier.weight(1f),
                onLeaderboards = if (state.leaderboards.isNotEmpty()) ({ showBoards = true }) else null,
                paused = when { state.cheatsPaused -> tk("PAUSED: CHEATS ON"); state.shinyOddsPaused -> tk("PAUSED: SHINY ODDS"); state.flightPaused -> tk("PAUSED: FLIGHT AREA"); else -> null },
                listState = listState,
                marked = marked,
            )
        }
    }
    if (picking) {
        OptionSelector(
            tk("FILTER"), AchievementFilter.entries, filter, { tk(it.label) }, m,
            onPick = { filter = it; picking = false },
            onDismiss = { picking = false },
        )
    }
}

@Composable
private fun AchievementList(
    list: List<Achievement>,
    game: AchievementGame,
    filter: AchievementFilter,
    onFilter: () -> Unit,
    m: GbaTextMetrics,
    small: GbaTextMetrics,
    modifier: Modifier,
    /** Switches to the leaderboards; null = the game has none. */
    onLeaderboards: (() -> Unit)?,
    /** Why nothing unlocks now (a cheat on, SHINY ODDS raised: RetroAchievements), or null. */
    paused: String? = null,
    listState: LazyListState = rememberLazyListState(),
    /** The achievement a popup opened: scrolled to and drawn as the cursor row. */
    marked: AchievementFocus? = null,
) {
    val shown = list.filter { filter.shows(it) }
    val target = marked?.let { f -> shown.indexOfFirst { it.id == f.id } } ?: -1
    // Scrolls to it - and again for a moment after, as the list re-sorts once the unlock is
    // reported (it moves up to RECENTLY UNLOCKED), but not later, under a player scrolling.
    LaunchedEffect(marked, target) {
        val f = marked ?: return@LaunchedEffect
        if (target >= 0 && System.nanoTime() - f.at < FOLLOW_MARKED_NANOS) listState.animateScrollToItem(target)
    }
    Column(modifier.fillMaxWidth()) {
        Row(
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(m.u * 4),
            modifier = Modifier.fillMaxWidth(),
        ) {
            BackdropText(game.title.orEmpty(), m, Modifier.weight(1f).padding(start = m.u * 4))
            paused?.let { OptionBadge(it, m) }
            OptionButton(
                tk(filter.short), small, onClick = onFilter, pixelIcon = PixelIcons.filter,
                emphasis = filter != AchievementFilter.ALL, contentDescription = tr("FILTER"),
            )
            onLeaderboards?.let { OptionButton(tk("LEADERBOARDS"), small, onClick = it) }
        }
        Spacer(Modifier.height(m.u * 4))
        OptionListWindow(m, Modifier.fillMaxWidth().weight(1f)) {
            if (shown.isEmpty()) {
                GbaText(
                    if (filter == AchievementFilter.UNLOCKED) tr("NONE UNLOCKED YET") else tr("ALL UNLOCKED!"),
                    OptionColors.value, OptionColors.valueShadow, m, Modifier.padding(m.u * 6),
                )
            } else {
                LazyColumn(Modifier.fillMaxSize(), state = listState) {
                    itemsIndexed(shown, key = { _, a -> a.id }) { i, a ->
                        if (i == 0 || shown[i - 1].section != a.section) {
                            // rc_client's English bucket labels (LOCKED, ALMOST THERE...); a subset's carry its title.
                            GbaText(
                                tr(a.section), OptionColors.muted, OptionColors.mutedShadow, small,
                                Modifier.padding(start = m.u * 6, top = m.u * if (i == 0) 2 else 6, bottom = m.u * 2),
                            )
                        }
                        AchievementRow(
                            a, m, small, divider = i < shown.lastIndex && shown[i + 1].section == a.section,
                            selected = a.id == marked?.id,
                        )
                    }
                }
            }
        }
    }
}

/** The game's leaderboards, grouped by set; tap one for its page. */
@Composable
private fun LeaderboardList(
    boards: List<Leaderboard>,
    game: AchievementGame,
    m: GbaTextMetrics,
    small: GbaTextMetrics,
    modifier: Modifier,
    onAchievements: () -> Unit,
    onOpen: (Int) -> Unit,
) {
    Column(modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            BackdropText(game.title.orEmpty(), m, Modifier.weight(1f).padding(start = m.u * 4))
            OptionButton(tk("ACHIEVEMENTS"), small, onClick = onAchievements)
        }
        Spacer(Modifier.height(m.u * 4))
        OptionListWindow(m, Modifier.fillMaxWidth().weight(1f)) {
            LazyColumn(Modifier.fillMaxSize()) {
                itemsIndexed(boards, key = { _, b -> b.id }) { i, b ->
                    if (i == 0 || boards[i - 1].section != b.section) {
                        GbaText(
                            tr(b.section.ifEmpty { tk("LEADERBOARDS") }), OptionColors.muted, OptionColors.mutedShadow, small,
                            Modifier.padding(start = m.u * 6, top = m.u * if (i == 0) 2 else 6, bottom = m.u * 2),
                        )
                    }
                    val u = m.u
                    val press = remember { MutableInteractionSource() }
                    val focused by press.collectIsFocusedAsState()
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            // The D-pad's cursor (the companion's window): the white row.
                            .background(if (focused) OptionColors.rowSelected else Color.Transparent)
                            .then(
                                if (i < boards.lastIndex && boards[i + 1].section == b.section) {
                                    Modifier.drawBehind { drawRowDivider(OptionColors.divider, u.toPx(), 4 * u.toPx()) }
                                } else Modifier,
                            )
                            .soundClickable(interactionSource = press, indication = null) { onOpen(b.id) }
                            .padding(horizontal = u * 8, vertical = u * 5),
                    ) {
                        Column(Modifier.weight(1f)) {
                            GbaText(b.title, OptionColors.label, OptionColors.labelShadow, m)
                            GbaText(b.description, OptionColors.muted, OptionColors.mutedShadow, small, maxLines = 2)
                        }
                        Spacer(Modifier.width(u * 6))
                        PixelIcon(PixelIcons.cursorRight, OptionColors.value, Modifier.width(m.lineHeight * 0.3f).height(m.lineHeight * 0.5f))
                    }
                }
            }
        }
    }
}

/** One leaderboard: its top entries, then (if they aren't among them) the ones around the player's. */
@Composable
private fun LeaderboardPageView(
    board: Leaderboard,
    page: LeaderboardPage?,
    m: GbaTextMetrics,
    small: GbaTextMetrics,
    modifier: Modifier,
    onBack: () -> Unit,
) {
    Column(modifier.fillMaxSize()) {
        OptionTitleWindow(board.title, m, trailing = page?.total?.takeIf { it > 0 }?.let { tr("{0} ENTRIES", it) }, onBack = onBack)
        Spacer(Modifier.height(m.u * 4))
        val top = page?.top.orEmpty()
        val near = page?.nearMe.orEmpty().filter { e -> top.none { it.rank == e.rank && it.user == e.user } }
        when {
            page == null || (page.loading && top.isEmpty()) -> Message(tr("LOADING"), board.description, m, small, loading = true)
            top.isEmpty() && page.error != null -> Message(tr("COULDN'T LOAD THE LEADERBOARD"), page.error.orEmpty(), m, small)
            top.isEmpty() -> Message(tr("NO ENTRIES YET"), board.description, m, small)
            else -> OptionListWindow(m, Modifier.fillMaxWidth().weight(1f)) {
                LazyColumn(Modifier.fillMaxSize()) {
                    item {
                        GbaText(
                            board.description, OptionColors.muted, OptionColors.mutedShadow, small, maxLines = 2,
                            modifier = Modifier.padding(start = m.u * 6, top = m.u * 2, bottom = m.u * 4),
                        )
                    }
                    itemsIndexed(top) { i, e -> EntryRow(e, m, divider = i < top.lastIndex) }
                    if (near.isNotEmpty()) {
                        // A gap only when the ranks really skip some.
                        if (near.first().rank > (top.lastOrNull()?.rank ?: 0) + 1) item {
                            GbaText("· · ·", OptionColors.muted, OptionColors.mutedShadow, m, Modifier.padding(start = m.u * 12, top = m.u * 2, bottom = m.u * 2))
                        }
                        else item { Spacer(Modifier.fillMaxWidth().height(m.u).drawBehind { drawRowDivider(OptionColors.divider, m.u.toPx(), 4 * m.u.toPx()) }) }
                        itemsIndexed(near) { i, e -> EntryRow(e, m, divider = i < near.lastIndex) }
                    }
                }
            }
        }
    }
}

/** `#RANK  USER        SCORE`; the player's own row is the white cursor row. */
@Composable
private fun EntryRow(e: LeaderboardEntry, m: GbaTextMetrics, divider: Boolean) {
    val u = m.u
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .height(m.rowHeight)
            // The player's own row stays white; the D-pad walks (and scrolls) the others (the companion's window).
            .focusRow(selected = e.isMe)
            .then(if (divider) Modifier.drawBehind { drawRowDivider(OptionColors.divider, u.toPx(), 4 * u.toPx()) } else Modifier)
            .padding(horizontal = u * 8),
    ) {
        GbaText("#${e.rank}", OptionColors.value, OptionColors.valueShadow, m, Modifier.width(u * 60))
        GbaText(e.user, OptionColors.label, OptionColors.labelShadow, m, Modifier.weight(1f))
        GbaText(e.score, OptionColors.value, OptionColors.valueShadow, m)
    }
}

@Composable
private fun AchievementRow(a: Achievement, m: GbaTextMetrics, small: GbaTextMetrics, divider: Boolean, selected: Boolean = false) {
    val u = m.u
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            // A stop for the D-pad, so the list can be walked and scrolled (the companion's window); white while focused.
            .focusRow(selected)
            .then(if (divider) Modifier.drawBehind { drawRowDivider(OptionColors.divider, u.toPx(), 4 * u.toPx()) } else Modifier)
            .padding(horizontal = u * 6, vertical = u * 4),
    ) {
        AchievementBadge(if (a.unlocked) a.badgeUrl else a.lockedBadgeUrl ?: a.badgeUrl, m.lineHeight * 1.8f, dim = !a.unlocked)
        Spacer(Modifier.width(u * 6))
        Column(Modifier.weight(1f)) {
            GbaText(a.title, if (a.unlocked) OptionColors.value else OptionColors.label, if (a.unlocked) OptionColors.valueShadow else OptionColors.labelShadow, m)
            GbaText(a.description, OptionColors.muted, OptionColors.mutedShadow, small, maxLines = 2)
            if (!a.unlocked && a.progress.isNotEmpty()) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = u * 2)) {
                    ProgressBar(a.percent / 100f, Modifier.width(u * 60).height(u * 4))
                    Spacer(Modifier.width(u * 4))
                    GbaText(a.progress, OptionColors.label, OptionColors.labelShadow, small)
                }
            }
        }
        Spacer(Modifier.width(u * 6))
        Column(horizontalAlignment = Alignment.End) {
            GbaText("${a.points}", OptionColors.value, OptionColors.valueShadow, m)
            GbaText(tr("PTS"), OptionColors.muted, OptionColors.mutedShadow, small)
        }
    }
}

/** A thin bar in the HP bar's idiom: dark frame, red fill. */
@Composable
private fun ProgressBar(fraction: Float, modifier: Modifier) {
    val m = rememberGbaTextMetrics()
    Box(
        modifier.drawBehind {
            val px = m.u.toPx()
            drawPixelRoundRect(OptionColors.frameDark, radius = px)
            drawRect(if (OptionColors.dark) OptionColors.rowSelected else Color.White, Offset(px, px), Size(size.width - 2 * px, size.height - 2 * px))
            val w = (size.width - 2 * px) * fraction.coerceIn(0f, 1f)
            if (w > 0f) drawRect(OptionColors.value, Offset(px, px), Size(w, size.height - 2 * px))
        },
    )
}

@Composable
private fun Message(
    title: String,
    body: String,
    m: GbaTextMetrics,
    small: GbaTextMetrics,
    loading: Boolean = false,
    detail: String? = null,
    action: (@Composable () -> Unit)? = null,
) {
    OptionListWindow(m, Modifier.fillMaxSize()) {
        Column(
            Modifier.fillMaxSize().padding(horizontal = m.u * 16, vertical = m.u * 8),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (loading) {
                RockingPokeBall(Modifier.size(m.u * 30))
                Spacer(Modifier.height(m.u * 6))
            }
            GbaText(title, OptionColors.value, OptionColors.valueShadow, rememberGbaTextMetrics(1.6f))
            Spacer(Modifier.height(m.u * 6))
            GbaText(body, OptionColors.label, OptionColors.labelShadow, m, maxLines = 5)
            detail?.let {
                Spacer(Modifier.height(m.u * 8))
                GbaText(it, OptionColors.muted, OptionColors.mutedShadow, small)
            }
            action?.let {
                Spacer(Modifier.height(m.u * 8))
                it()
            }
        }
    }
}

/**
 * Which popup host shows a popup: the companion's, on whichever screen the player sees it, else the one over the game.
 * A DS game's touch screen sits where the companion goes on a GBA game (and one screen keeps the companion closed in its
 * window or panel), so its popups played out unseen on a hidden companion: GitHub #500. Main thread (the hosts collect on
 * Compose's dispatcher).
 */
object AchievementPopupRoute {
    /** A host's view is on a screen the player sees (the app: its View's isShown); previews and tests: always. */
    var shownProbe: @Composable () -> (() -> Boolean) = { { true } }

    private val companions = LinkedHashSet<() -> Boolean>()

    internal fun add(shown: () -> Boolean) { companions += shown }
    internal fun remove(shown: () -> Boolean) { companions -= shown }

    /** A companion's popup host is on screen now. */
    fun companionSeen(): Boolean = companions.any { it() }

    /** Whether a host takes a popup: the companion's while it's seen, the game's ([overGame]) while none is. */
    fun takes(overGame: Boolean, shown: () -> Boolean): Boolean = if (overGame) !companionSeen() else shown()
}

/**
 * Popups over the whole companion, top center, one at a time: unlocks,
 * mastery, the "achievements loaded" note, progress, leaderboards and
 * connection notices. Tap one to dismiss it early - an unlock (or its progress)
 * also opens it in the ACHIEVEMENTS list, mastery the list ([onOpen]). [initial]
 * shows one right away (screenshot tests). [overGame]: the host over the game, which shows them while no companion is
 * on screen ([AchievementPopupRoute]).
 */
@Composable
fun AchievementPopupHost(
    achievements: CompanionAchievements?,
    modifier: Modifier = Modifier,
    initial: AchievementPopup? = null,
    onOpen: ((AchievementPopup) -> Unit)? = null,
    overGame: Boolean = false,
) {
    if (initial != null) {
        // Screenshot tests: shown as is, no timers.
        Box(modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) { PopupCard(initial, onTap = {}) }
        return
    }
    val queue = remember { mutableListOf<AchievementPopup>() }
    val wake = remember { Channel<Unit>(Channel.CONFLATED) }
    val dismiss = remember { Channel<Unit>(Channel.CONFLATED) }
    var current by remember { mutableStateOf<AchievementPopup?>(null) }
    var visible by remember { mutableStateOf(false) }
    val shown = AchievementPopupRoute.shownProbe()
    if (!overGame) {
        DisposableEffect(shown) {
            AchievementPopupRoute.add(shown)
            onDispose { AchievementPopupRoute.remove(shown) }
        }
    }
    LaunchedEffect(achievements) {
        achievements?.popups?.collect { p ->
            if (!AchievementPopupRoute.takes(overGame, shown)) return@collect
            // A newer progress update replaces one still waiting.
            if (p.kind == AchievementPopup.Kind.PROGRESS) queue.removeAll { it.kind == p.kind && it.title == p.title }
            queue.add(p)
            wake.trySend(Unit)
        }
    }
    LaunchedEffect(Unit) {
        while (true) {
            if (queue.isEmpty()) wake.receive()
            val next = queue.removeFirstOrNull() ?: continue
            current = next
            visible = true
            dismiss.tryReceive()
            withTimeoutOrNull(
                when (next.kind) {
                    AchievementPopup.Kind.UNLOCKED, AchievementPopup.Kind.MASTERED -> 5000L
                    AchievementPopup.Kind.PROGRESS -> 2500L
                    else -> 4000L
                },
            ) { dismiss.receive() }
            visible = false
            delay(250)
        }
    }
    Box(modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
        AnimatedVisibility(
            visible = visible && current != null,
            enter = slideInVertically(tween(220)) { -it } + fadeIn(tween(220)),
            exit = slideOutVertically(tween(200)) { -it } + fadeOut(tween(200)),
        ) {
            current?.let { p ->
                PopupCard(p, onTap = {
                    if (p.opensList) onOpen?.invoke(p)
                    dismiss.trySend(Unit)
                })
            }
        }
    }
}

/** A tap on this popup opens the ACHIEVEMENTS list (on its achievement, when it has one). */
val AchievementPopup.opensList: Boolean
    get() = achievementId != 0 || kind == AchievementPopup.Kind.MASTERED

@Composable
private fun PopupCard(p: AchievementPopup, onTap: () -> Unit) {
    val m = rememberGbaTextMetrics()
    val small = rememberGbaTextMetrics(1f)
    val u = m.u
    val header = when (p.kind) {
        AchievementPopup.Kind.UNLOCKED -> tr("ACHIEVEMENT UNLOCKED")
        AchievementPopup.Kind.MASTERED -> tr("GAME COMPLETE")
        AchievementPopup.Kind.GAME_LOADED -> "RetroAchievements"
        AchievementPopup.Kind.PROGRESS -> tr("PROGRESS")
        AchievementPopup.Kind.LEADERBOARD -> tr("LEADERBOARD")
        AchievementPopup.Kind.NOTICE -> "RetroAchievements"
    }
    val badge = p.kind != AchievementPopup.Kind.NOTICE && p.kind != AchievementPopup.Kind.LEADERBOARD
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .widthIn(max = 720.dp)
            .fillMaxWidth(0.8f)
            .drawBehind { drawLayeredBox(OptionColors.listLayers.inPx(u.toPx()), OptionColors.titleFill, radius = 3 * u.toPx()) }
            .soundClickable(onClick = onTap)
            .padding(horizontal = u * 12, vertical = u * 8),
    ) {
        if (badge) {
            AchievementBadge(p.badgeUrl, m.lineHeight * 2f)
            Spacer(Modifier.width(u * 8))
        }
        Column(Modifier.weight(1f)) {
            GbaText(header, OptionColors.value, OptionColors.valueShadow, small)
            GbaText(p.title, OptionColors.label, OptionColors.labelShadow, m)
            if (p.detail.isNotEmpty()) GbaText(p.detail, OptionColors.muted, OptionColors.mutedShadow, small, maxLines = 2)
        }
        if (p.points > 0) {
            Spacer(Modifier.width(u * 8))
            Column(horizontalAlignment = Alignment.End) {
                GbaText("${p.points}", OptionColors.value, OptionColors.valueShadow, m)
                GbaText(tr("PTS"), OptionColors.muted, OptionColors.mutedShadow, small)
            }
        }
    }
}

/**
 * What's live right now, over every tab (bottom right, clear of the popups up top):
 * running leaderboard attempts' values (a white box each, the time / score in red)
 * and the badges of achievements whose challenge is under way. Nothing when idle.
 */
@Composable
fun AchievementIndicators(achievements: CompanionAchievements?, modifier: Modifier = Modifier) {
    if (!AchievementIcons.shown) return
    val state = achievements?.state?.collectAsState()?.value ?: return
    if (state.trackers.isEmpty() && state.challenges.isEmpty()) return
    val m = rememberGbaTextMetrics()
    val u = m.u
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(u * 4), verticalAlignment = Alignment.CenterVertically) {
        state.trackers.forEach { t ->
            Box(
                Modifier
                    .drawBehind { drawLayeredBox(OptionColors.titleLayers.inPx(u.toPx()), OptionColors.titleFill, radius = 2 * u.toPx()) }
                    .padding(horizontal = u * 8, vertical = u * 4),
            ) {
                GbaText(t.display, OptionColors.value, OptionColors.valueShadow, m)
            }
        }
        state.challenges.forEach { c -> AchievementBadge(c.badgeUrl, m.lineHeight * 1.6f) }
    }
}

/** SETTINGS > CHALLENGE ICONS ([com.pokedaisy.app.Prefs.raIcons], GitHub discussion #620): off, [AchievementIndicators] draws
 *  nothing (the badges sat over the companion's corner). Compose state: both screens follow a flip at once. */
object AchievementIcons {
    var shown by androidx.compose.runtime.mutableStateOf(true)
}

/** How long the list keeps scrolling to a popup's achievement as it re-sorts (see [AchievementList]). */
private const val FOLLOW_MARKED_NANOS = 3_000_000_000L
