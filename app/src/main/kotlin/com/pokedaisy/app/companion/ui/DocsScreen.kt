package com.pokedaisy.app.companion.ui

import kotlin.concurrent.Volatile
import com.pokedaisy.app.companion.data.LockedLru
import android.content.Context
import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.ClickableText
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import com.pokedaisy.app.DocsApi
import com.pokedaisy.app.DocsApi.Block
import com.pokedaisy.app.DocsSearch
import com.pokedaisy.app.DocsStore
import com.pokedaisy.app.companion.i18n.tk
import com.pokedaisy.app.companion.ui.theme.pixelFontFamily
import com.pokedaisy.app.companion.i18n.tr
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Where the manual comes from: the website's docs API, kept on disk ([StoreDocsSource]); ui-preview reads
 * the site's local build instead.
 */
interface DocsSource {
    /** The copy kept from last time, or null. Blocking. */
    fun cached(): DocsApi.Manual?
    /** Asks the site for a newer manual. Blocking. */
    fun refresh(): DocsStore.Refresh
    /** An image block's picture, or null (offline and never shown before). Blocking. */
    fun image(manual: DocsApi.Manual, image: Block.Image): Bitmap?
}

/** The source both screens read: the docs API through [DocsStore], unless [override] (ui-preview's local build) is set. */
object DocsSources {
    @Volatile var override: DocsSource? = null
    fun of(context: Context): DocsSource = override ?: StoreDocsSource(context)
}

class StoreDocsSource(context: Context) : DocsSource {
    private val app = context.applicationContext
    override fun cached() = DocsStore.cached(app)
    override fun refresh() = DocsStore.refresh(app).also { if (it is DocsStore.Refresh.Updated) DocsStore.pruneImages(app, it.manual) }
    override fun image(manual: DocsApi.Manual, image: Block.Image) = DocsStore.image(app, manual, image)
}

/**
 * The docs reader's place: the manual once loaded, the open page (null = the contents), pages opened
 * before it (BACK steps through them) and the search. Held by the screen that hosts the reader, so a
 * visit elsewhere and back lands on the same page.
 */
class DocsUiState(initialPage: String? = null, initialQuery: String = "") {
    enum class Status { LOADING, READY, OFFLINE }

    internal var manual by mutableStateOf<DocsApi.Manual?>(null)
    internal var status by mutableStateOf(Status.LOADING)
    var page by mutableStateOf(initialPage)
        private set
    internal var anchor by mutableStateOf<String?>(null)
    internal var query by mutableStateOf(initialQuery)
    private val history = mutableStateListOf<String>()
    internal val contents = LazyListState()
    internal var search: DocsSearch? = null
    internal var started = false

    /** Opens [slug] (at [anchor], a heading's id), remembering the page it was opened from. */
    fun open(slug: String, anchor: String? = null) {
        page?.takeIf { it != slug }?.let { history.add(it) }
        page = slug
        this.anchor = anchor
    }

    /** One step back: the page before, else the contents. False when already on the contents. */
    fun back(): Boolean {
        if (page == null) return false
        page = history.removeLastOrNull()
        anchor = null
        return true
    }

    internal fun searchIndex(): DocsSearch? = manual?.let { m -> search ?: DocsSearch(m).also { search = it } }
}

/**
 * The manual, in the app's OPTION look: a contents list (and, where a keyboard can be had, a search box),
 * then pages of reading text. Headings and labels keep the pixel font; paragraphs are Atkinson Hyperlegible,
 * easier over a long read. Both screens host it: top-screen Settings > HELP and the companion's SETTINGS >
 * HELP ([searchable] false there: the bottom screen can't host a keyboard). [onOpenUrl] opens outside links;
 * null leaves them as plain text.
 */
@Composable
fun DocsReader(
    ui: DocsUiState,
    m: GbaTextMetrics,
    source: DocsSource,
    modifier: Modifier = Modifier,
    searchable: Boolean = true,
    onOpenUrl: ((String) -> Unit)? = null,
) {
    val scope = rememberCoroutineScope()
    fun load() {
        ui.status = DocsUiState.Status.LOADING
        scope.launch {
            val kept = ui.manual ?: withContext(Dispatchers.IO) { source.cached() }
            if (kept != null && ui.manual == null) { ui.manual = kept; ui.search = null }
            if (kept != null) ui.status = DocsUiState.Status.READY
            when (val r = withContext(Dispatchers.IO) { source.refresh() }) {
                is DocsStore.Refresh.Updated -> { ui.manual = r.manual; ui.search = null; ui.status = DocsUiState.Status.READY }
                DocsStore.Refresh.Unchanged -> ui.status = DocsUiState.Status.READY
                is DocsStore.Refresh.Failed -> if (ui.manual == null) ui.status = DocsUiState.Status.OFFLINE
            }
        }
    }
    LaunchedEffect(Unit) { if (!ui.started) { ui.started = true; load() } }

    val manual = ui.manual
    Column(modifier.fillMaxSize()) {
        val page = ui.page?.let { manual?.page(it) }
        when {
            manual == null -> OptionListWindow(m, Modifier.fillMaxSize()) {
                Column(Modifier.fillMaxWidth().padding(m.u * 8), verticalArrangement = Arrangement.spacedBy(m.u * 6)) {
                    if (ui.status == DocsUiState.Status.LOADING) {
                        GbaText(tr("LOADING THE MANUAL…"), OptionColors.label, OptionColors.labelShadow, m)
                    } else {
                        Text(
                            tr("The manual comes from pokedaisy.com and is kept for reading offline. Connect to the internet once to get it."),
                            style = readingStyle(m), color = OptionColors.label,
                        )
                        OptionButton(tr("RETRY"), m, onClick = ::load)
                    }
                }
            }
            page != null -> DocsPage(ui, manual, page, m, source, onOpenUrl)
            else -> DocsContents(ui, manual, m, searchable)
        }
    }
}

/** Paragraph text: Atkinson Hyperlegible, sized to sit beside the pixel font's lines. */
@Composable
private fun readingStyle(m: GbaTextMetrics, scale: Float = 0.62f): TextStyle {
    val size = m.fontSize * scale
    return TextStyle(
        fontFamily = com.pokedaisy.app.readingFontFamily(LocalContext.current),
        fontSize = size,
        lineHeight = size * 1.5f,
        color = OptionColors.readingText,
    )
}

// ---- contents and search ------------------------------------------------------------------------

@Composable
private fun DocsContents(ui: DocsUiState, manual: DocsApi.Manual, m: GbaTextMetrics, searchable: Boolean) {
    val small = rememberGbaTextMetrics(1f)
    Column(Modifier.fillMaxSize()) {
    if (searchable) {
        OptionTextField(ui.query, { ui.query = it }, m, Modifier.fillMaxWidth(), placeholder = tr("SEARCH THE MANUAL (e.g. FAST-FORWARD)"))
        Spacer(Modifier.height(m.u * 4))
    }
    val q = ui.query.trim()
    OptionListWindow(m, Modifier.fillMaxWidth().weight(1f), fill = OptionColors.readingPaper) {
        if (searchable && q.isNotEmpty()) {
            val hits = remember(manual, q) { ui.searchIndex()?.search(q, 20).orEmpty() }
            val list = rememberLazyListState()
            LazyColumn(Modifier.fillMaxSize(), state = list) {
                if (hits.isEmpty()) item {
                    GbaText(tr("NOTHING MATCHES \"{0}\"", q), OptionColors.muted, OptionColors.mutedShadow, m, Modifier.padding(m.u * 8))
                }
                hits.forEachIndexed { i, hit ->
                    item(key = "${hit.page}#${hit.anchor}") {
                        DocsRow(
                            over = (if (hit.heading != null) hit.pageTitle else manual.section(manual.page(hit.page)?.section ?: "")?.title ?: "").uppercase(),
                            title = hit.heading ?: hit.pageTitle,
                            text = marked(hit.snippet, hit.marks),
                            m = m, small = small, divider = i < hits.lastIndex,
                        ) { ui.open(hit.page, hit.anchor) }
                    }
                }
            }
        } else {
            LazyColumn(Modifier.fillMaxSize(), state = ui.contents) {
                manual.sections.forEach { section ->
                    item(key = "s:${section.id}") {
                        GbaText(
                            section.title.uppercase(), OptionColors.groupTitleText, OptionColors.groupTitleShadow, m,
                            Modifier.padding(start = m.u * 8, end = m.u * 8, top = m.u * 8, bottom = m.u * 2),
                        )
                    }
                    section.pages.mapNotNull(manual::page).forEachIndexed { i, page ->
                        item(key = page.slug) {
                            DocsRow(null, page.title, AnnotatedString(page.summary), m, small, divider = i < section.pages.lastIndex) { ui.open(page.slug) }
                        }
                    }
                }
            }
        }
    }
    }
}

/** [text] with [marks] (a search hit's matched words) highlighted. */
private fun marked(text: String, marks: List<IntRange>): AnnotatedString = buildAnnotatedString {
    append(text)
    for (r in marks) if (r.first >= 0 && r.last < text.length) addStyle(SpanStyle(background = OptionColors.readingMark), r.first, r.last + 1)
}

/** A contents / search row: an optional small label over the title (pixel font), then a line of reading text. */
@Composable
private fun DocsRow(
    over: String?, title: String, text: AnnotatedString, m: GbaTextMetrics, small: GbaTextMetrics,
    divider: Boolean, onClick: () -> Unit,
) {
    Column(
        Modifier.fillMaxWidth()
            .soundClickable(onClick = onClick)
            .then(if (divider) Modifier.drawRowDividerBehind(m) else Modifier)
            .padding(horizontal = m.u * 8, vertical = m.u * 5),
        verticalArrangement = Arrangement.spacedBy(m.u * 2),
    ) {
        if (!over.isNullOrEmpty()) GbaText(over, OptionColors.value, OptionColors.valueShadow, small)
        GbaText(title, OptionColors.label, OptionColors.labelShadow, m, maxLines = 2)
        if (text.isNotEmpty()) Text(text, style = readingStyle(m, 0.56f), color = OptionColors.readingMuted, maxLines = 3)
    }
}

private fun Modifier.drawRowDividerBehind(m: GbaTextMetrics) =
    drawBehind { drawRowDivider(OptionColors.divider, m.u.toPx(), 4 * m.u.toPx()) }

// ---- a page ---------------------------------------------------------------------------------------

@Composable
private fun DocsPage(
    ui: DocsUiState, manual: DocsApi.Manual, page: DocsApi.Page, m: GbaTextMetrics, source: DocsSource,
    onOpenUrl: ((String) -> Unit)?,
) {
    val list = remember(page.slug) { LazyListState() }
    val small = rememberGbaTextMetrics(1f)
    // Item 0 is the page's header; a heading's item is its block's index + 1.
    LaunchedEffect(page.slug, ui.anchor) {
        val a = ui.anchor ?: return@LaunchedEffect
        val i = page.blocks.indexOfFirst { it is Block.Heading && it.id == a }
        if (i >= 0) list.scrollToItem(i + 1)
        ui.anchor = null
    }
    val link: (DocsApi.Span) -> Unit = { s ->
        when {
            s.page != null && s.page == page.slug && s.anchor != null -> ui.anchor = s.anchor
            s.page != null -> ui.open(s.page, s.anchor)
            s.href != null -> onOpenUrl?.invoke(s.href)
        }
    }
    OptionListWindow(m, Modifier.fillMaxSize(), fill = OptionColors.readingPaper) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
            LazyColumn(Modifier.fillMaxHeight().widthIn(max = DOCS_MAX_WIDTH).fillMaxWidth(), state = list) {
                item(key = "head") {
                    Column(Modifier.padding(start = m.u * 8, end = m.u * 8, top = m.u * 6, bottom = m.u * 2)) {
                        manual.section(page.section)?.let {
                            GbaText(it.title.uppercase(), OptionColors.value, OptionColors.valueShadow, small)
                            Spacer(Modifier.height(m.u * 3))
                        }
                        GbaText(page.title, OptionColors.label, OptionColors.labelShadow, m, maxLines = 3)
                        Spacer(Modifier.height(m.u * 3))
                        Text(page.summary, style = readingStyle(m), color = OptionColors.readingMuted)
                    }
                }
                page.blocks.forEachIndexed { i, b ->
                    item(key = i) {
                        Box(Modifier.padding(horizontal = m.u * 8)) { DocBlock(b, manual, m, small, source, link, onOpenUrl != null) }
                    }
                }
                item(key = "foot") { PageFoot(ui, manual, page, m, small) }
            }
        }
    }
}

@Composable
private fun PageFoot(ui: DocsUiState, manual: DocsApi.Manual, page: DocsApi.Page, m: GbaTextMetrics, small: GbaTextMetrics) {
    val prev = manual.previous(page.slug)
    val next = manual.next(page.slug)
    Column(Modifier.padding(m.u * 8)) {
        Separator(m, Modifier.fillMaxWidth())
        Spacer(Modifier.height(m.u * 8))
        Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(m.u * 8)) {
            if (prev != null) FootLink(tr("PREVIOUS"), prev.title, m, small, Modifier.weight(1f)) { ui.open(prev.slug) }
            else Spacer(Modifier.weight(1f))
            if (next != null) FootLink(tr("NEXT"), next.title, m, small, Modifier.weight(1f), end = true) { ui.open(next.slug) }
            else Spacer(Modifier.weight(1f))
        }
    }
}

@Composable
private fun FootLink(label: String, title: String, m: GbaTextMetrics, small: GbaTextMetrics, modifier: Modifier, end: Boolean = false, onClick: () -> Unit) {
    Column(
        modifier.fillMaxHeight()
            .drawWindow(m)
            .soundClickable(onClick = onClick)
            .padding(horizontal = m.u * 8, vertical = m.u * 5),
        horizontalAlignment = if (end) Alignment.End else Alignment.Start,
    ) {
        GbaText(label, OptionColors.value, OptionColors.valueShadow, small)
        Spacer(Modifier.height(m.u * 2))
        GbaText(title, OptionColors.label, OptionColors.labelShadow, m, maxLines = 2)
    }
}

/** The title window's frame behind a composable (the look of [OptionButton]). */
private fun Modifier.drawWindow(m: GbaTextMetrics) = drawBehind {
    drawLayeredBox(OptionColors.titleLayers.inPx(m.u.toPx()), OptionColors.titleFill, radius = 3 * m.u.toPx())
}

/** One block (website/src/components/DocBlocks.astro draws the same ones as HTML). */
@Composable
private fun DocBlock(
    b: Block, manual: DocsApi.Manual, m: GbaTextMetrics, small: GbaTextMetrics, source: DocsSource,
    link: (DocsApi.Span) -> Unit, outsideLinks: Boolean,
) {
    when (b) {
        is Block.Heading -> if (b.level == 2) {
            Column(Modifier.fillMaxWidth().padding(top = m.u * 12, bottom = m.u * 4)) {
                Separator(m, Modifier.fillMaxWidth())
                Spacer(Modifier.height(m.u * 8))
                GbaText(b.text, OptionColors.label, OptionColors.labelShadow, m, maxLines = 3)
            }
        } else {
            GbaText(b.text, OptionColors.label, OptionColors.labelShadow, small, Modifier.padding(top = m.u * 8, bottom = m.u * 2), maxLines = 3)
        }
        is Block.Paragraph -> Paragraph(b.spans, m, link, outsideLinks, Modifier.padding(vertical = m.u * 3))
        is Block.Bullets -> Column(Modifier.padding(vertical = m.u * 2)) {
            val style = readingStyle(m)
            // The marker sits on the item's first line: a paragraph's padding plus half its line.
            val firstLine = with(androidx.compose.ui.platform.LocalDensity.current) { style.lineHeight.toDp() }
            b.items.forEachIndexed { i, item ->
                Row(Modifier.padding(vertical = m.u)) {
                    Box(Modifier.width(m.u * if (b.ordered) 16 else 12)) {
                        if (b.ordered) Text("${b.start + i}.", style = style, color = OptionColors.value, modifier = Modifier.padding(top = m.u * 3))
                        else Box(Modifier.padding(top = m.u * 3 + firstLine / 2 - m.u).size(m.u * 2).background(OptionColors.value))
                    }
                    Column(Modifier.weight(1f)) { item.forEach { DocBlock(it, manual, m, small, source, link, outsideLinks) } }
                }
            }
        }
        is Block.Image -> DocImage(b, manual, m, small, source)
        is Block.Note -> Row(
            Modifier.fillMaxWidth().padding(vertical = m.u * 4).height(IntrinsicSize.Min).background(OptionColors.noteFill(b.kind)),
        ) {
            Box(Modifier.width(m.u * 2).fillMaxHeight().background(OptionColors.noteBar(b.kind)))
            Column(Modifier.weight(1f).padding(horizontal = m.u * 6, vertical = m.u * 4)) {
                GbaText(tr(NOTE_TAGS[b.kind] ?: NOTE_TAGS.getValue("note")), OptionColors.muted, OptionColors.mutedShadow, small)
                b.blocks.forEach { DocBlock(it, manual, m, small, source, link, outsideLinks) }
            }
        }
        is Block.Table -> DocTable(b, m, small, link, outsideLinks)
        Block.Rule -> Separator(m, Modifier.fillMaxWidth().padding(vertical = m.u * 8))
    }
}

private val NOTE_TAGS = mapOf("tip" to tk("TIP"), "note" to tk("NOTE"), "warning" to tk("HEADS UP"))

@Composable
private fun Paragraph(
    spans: List<DocsApi.Span>, m: GbaTextMetrics, link: (DocsApi.Span) -> Unit, outsideLinks: Boolean,
    modifier: Modifier = Modifier, scale: Float = 0.62f,
) {
    val style = readingStyle(m, scale)
    val pixel = pixelFontFamily()
    val text = remember(spans, OptionColors.dark, pixel) { annotate(spans, pixel, style.fontSize, outsideLinks) }
    if (text.getStringAnnotations(LINK, 0, text.length).isEmpty()) {
        Text(text, style = style, modifier = modifier.fillMaxWidth())
    } else {
        ClickableText(text, style = style, modifier = modifier.fillMaxWidth()) { offset ->
            text.getStringAnnotations(LINK, offset, offset).firstOrNull()?.let { a -> link(spans[a.item.toInt()]) }
        }
    }
}

private const val LINK = "link"

private fun annotate(spans: List<DocsApi.Span>, pixel: androidx.compose.ui.text.font.FontFamily, size: TextUnit, outsideLinks: Boolean) =
    buildAnnotatedString {
        spans.forEachIndexed { i, s ->
            val linked = s.page != null || (s.href != null && outsideLinks)
            val style = SpanStyle(
                fontWeight = if (s.bold) FontWeight.Bold else null,
                fontStyle = if (s.italic) FontStyle.Italic else null,
                // A button or setting name: in the app's pixel font, like on screen.
                fontFamily = if (s.code) pixel else null,
                fontSize = if (s.code) size * 1.3f else TextUnit.Unspecified,
                background = if (s.code) OptionColors.readingCode else Color.Unspecified,
                color = if (linked) OptionColors.readingLink else Color.Unspecified,
                textDecoration = if (linked) TextDecoration.Underline else null,
            )
            if (linked) pushStringAnnotation(LINK, i.toString())
            withStyle(style) { append(if (s.code) " ${s.text} " else s.text) }
            if (linked) pop()
        }
    }

/** Pictures already decoded this process (a page left and opened again shouldn't decode them twice). */
private val imageCache = LockedLru<String, ImageBitmap>(12)

@Composable
private fun DocImage(b: Block.Image, manual: DocsApi.Manual, m: GbaTextMetrics, small: GbaTextMetrics, source: DocsSource) {
    val key = b.src + "@" + b.version
    val bitmap by produceKeyedState(imageCache[key], key) {
        if (value == null) value = withContext(Dispatchers.IO) {
            source.image(manual, b)?.asImageBitmap()?.also { imageCache[key] = it }
        }
    }
    Column(Modifier.fillMaxWidth().padding(vertical = m.u * 5)) {
        val ratio = if (b.width > 0 && b.height > 0) b.width.toFloat() / b.height else 1.5f
        Box(
            Modifier.widthIn(max = 560.dp).fillMaxWidth().aspectRatio(ratio)
                .background(OptionColors.frameDark)
                .padding(m.u)
                .background(OptionColors.imageWell),
            contentAlignment = Alignment.Center,
        ) {
            val bmp = bitmap
            if (bmp != null) Image(bmp, b.alt, Modifier.fillMaxSize())
            else Text(b.alt, style = readingStyle(m, 0.5f), color = Color(0xFFB8B8C4), modifier = Modifier.padding(m.u * 8))
        }
        b.caption?.let {
            Spacer(Modifier.height(m.u * 3))
            Text(it, style = readingStyle(m, 0.52f), color = OptionColors.readingMuted, modifier = Modifier.widthIn(max = 560.dp))
        }
    }
}

@Composable
private fun DocTable(b: Block.Table, m: GbaTextMetrics, small: GbaTextMetrics, link: (DocsApi.Span) -> Unit, outsideLinks: Boolean) {
    // The first column holds names (a setting, a button): narrower than the explanations beside it.
    val weights = List(b.head.size) { if (it == 0 && b.head.size > 1) 0.8f else 1.4f }
    Column(Modifier.fillMaxWidth().padding(vertical = m.u * 4)) {
        Row(Modifier.fillMaxWidth().background(OptionColors.readingCode).padding(vertical = m.u * 3)) {
            b.head.forEachIndexed { i, cell ->
                GbaText(cell.joinToString("") { it.text }, OptionColors.label, OptionColors.labelShadow, small, Modifier.weight(weights[i]).padding(horizontal = m.u * 4), maxLines = 2)
            }
        }
        b.rows.forEach { row ->
            Row(Modifier.fillMaxWidth().drawRowDividerBehind(m).padding(vertical = m.u * 3)) {
                row.forEachIndexed { i, cell ->
                    Paragraph(cell, m, link, outsideLinks, Modifier.weight(weights.getOrElse(i) { 1f }).padding(horizontal = m.u * 4), scale = 0.56f)
                }
            }
        }
    }
}

/** How wide the reading column may get: past this a line is too long to read comfortably. */
val DOCS_MAX_WIDTH: Dp = 760.dp
