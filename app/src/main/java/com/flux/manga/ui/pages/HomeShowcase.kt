package com.flux.manga.ui.pages

import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.pager.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.flux.manga.data.*
import com.flux.manga.session.WorldSession
import com.flux.manga.ui.navigation.DiscoverySection
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch

@Composable
internal fun DiscoveryHomeShowcase(session: WorldSession, configuration: SourceDiscoveryModel, state: DiscoveryState,
    bottom: Dp, search: () -> Unit, sources: () -> Unit, settings: () -> Unit, switchError: String?,
    section: (DiscoverySection) -> Unit, open: (String, String) -> Unit, reportScroll: (LazyListState) -> Unit) {
    val recommendations = mixedModel(session, null, DiscoverySection.RECOMMENDATIONS)
    val recommendationState by recommendations.feed.state.collectAsStateWithLifecycle()
    val scroll = rememberLazyListState()
    val incomingRecommended = mixedCards(recommendations.feed.sources, recommendationState.results)
    val updates = if (state.status != DiscoveryStatus.LOADING) mixedModel(session, state.sourceId, DiscoverySection.UPDATES) else null
    val updateState = updates?.feed?.state?.collectAsStateWithLifecycle()?.value
    val incomingUpdated = if (updates != null && updateState != null) mixedCards(updates.feed.sources, updateState.results) else emptyList()
    val recommended = stableHomeCards(recommendations, incomingRecommended, scroll.isScrollInProgress)
    val updated = stableHomeCards(updates, incomingUpdated, scroll.isScrollInProgress)
    val featured = recommended.take(5)
    val remaining = recommended.drop(featured.size).take(9)
    val refreshScope = rememberCoroutineScope()
    var refreshing by remember(recommendations) { mutableStateOf(false) }
    SideEffect { reportScroll(scroll) }
    BookResultsLayout { columns ->
        ReferenceRefreshColumn(state = scroll, refreshing = refreshing,
            refresh = {
                refreshing = true
                refreshScope.launch {
                    try {
                        configuration.refresh()
                        listOfNotNull(recommendations, updates).flatMap { model ->
                            model.feed.sources.mapNotNull { model.feed.refreshNow(it) }
                        }.joinAll()
                    } finally { refreshing = false }
                }
            }, modifier = Modifier.fillMaxSize().testTag("discovery-home"),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 0.dp, bottom = bottom),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item("heading") { Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                DiscoveryHeading("home-more", settings)
                ReferenceSearchEntry("搜索书名或作者", search, Modifier.testTag("open-search"))
                switchError?.let { RequestFeedback(it) }
            } }
            item("featured") { Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                DiscoverySectionHeading("图源推荐", "open-recommendations-status", actions = {
                    CompactFeedAction(recommendations.feed, recommendationState, recommendations.resultFeeds, sources)
                }) { section(DiscoverySection.RECOMMENDATIONS) }
                HomeFeedState(recommendations, recommended, sources, featured = true, columns = columns)
                if (featured.isNotEmpty()) HomeFeaturedPager(session, recommendations, featured, open)
            } }
            item("updates-heading") { Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                DiscoverySectionHeading("最近更新", "open-source-updates-status", actions = {
                    if (updates != null && updateState != null) CompactFeedAction(updates.feed, updateState, updates.resultFeeds, sources)
                }) { section(DiscoverySection.UPDATES) }
                if (updates != null) HomeFeedState(updates, updated, sources, columns = columns)
                else HomeCoverPlaceholders(columns)
                if (state.status == DiscoveryStatus.FAILED) TextButton(configuration::refresh) { Text("重试图源配置") }
            } }
            if (updates != null) bookGridItems(updated.take(9), columns, { "${it.key.sourceId}:${it.key.mangaId}" }) { card ->
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    DiscoveryGridBook(updates.library, card, open)
                    HomeUpdateMetadata(updates, card)
                }
            }
            if (remaining.isNotEmpty()) {
                item("more-heading") { Box {
                    DiscoverySectionHeading("更多推荐", "home-more-recommendations") { section(DiscoverySection.RECOMMENDATIONS) }
                } }
                bookGridItems(remaining, columns, { "recommended:${it.key.sourceId}:${it.key.mangaId}" }) { card ->
                    DiscoveryGridBook(recommendations.library, card, open)
                }
            }
        }
    }
}

@Composable
private fun HomeFeedState(model: MixedDiscoveryModel, cards: List<MangaCard>, sources: () -> Unit,
    featured: Boolean = false, columns: Int = 3) {
    val state by model.feed.state.collectAsStateWithLifecycle()
    val pending = model.feed.sources.isNotEmpty() &&
        (state.results.isEmpty() || state.results.values.any { !it.attempted })
    CompactFeedFeedback(model.feed, state, model.resultFeeds, sources, showAction = false,
        loadingLabel = if (featured && cards.isNotEmpty()) "正在联网刷新推荐…" else "正在加载…",
        retainedLabel = if (featured) "已保留上次推荐" else null)
    if (cards.isEmpty()) when {
        state.busy || pending -> if (featured) HomeFeaturedPlaceholder() else HomeCoverPlaceholders(columns)
        state.results.values.any { it.error != null || it.appendError != null } -> ReadFailure(state.results.values.mapNotNull { it.error ?: it.appendError }.distinct().joinToString("\n")) {
            model.resultFeeds.forEach { feed ->
                val result = state.results[feed.id]
                if (result?.appendError != null) model.feed.more(feed.id)
                else if (result?.error != null) model.feed.refresh(feed.id)
            }
        }
        !hasMixedFeedback(state) -> Text(if (model.feed.sources.isEmpty()) "暂无可用图源" else "暂无作品", color = ReadingSurfaceStyle.muted)
    }
}

/** Both worlds keep the final recommendation-card geometry while restoring or fetching data. */
@Composable
private fun HomeFeaturedPlaceholder() {
    val height = featuredCardHeight()
    Surface(modifier = Modifier.readingCardShadow().semantics(mergeDescendants = true) { contentDescription = "正在加载推荐" }, shape = ReadingSurfaceStyle.cardShape,
        color = ReadingSurfaceStyle.card, border = ReadingSurfaceStyle.border) {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val coverWidth = featuredCoverWidth(maxWidth, height)
            Row(Modifier.fillMaxWidth().height(height), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Spacer(Modifier.width(coverWidth).fillMaxHeight()
                    .background(MaterialTheme.colorScheme.surfaceVariant))
                Column(Modifier.weight(1f).padding(top = 8.dp, bottom = 8.dp, end = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    ReadPlaceholder(Modifier.fillMaxWidth().height(24.dp))
                    ReadPlaceholder(Modifier.fillMaxWidth(.6f).height(16.dp))
                    ReadPlaceholder(Modifier.fillMaxWidth(.75f).height(20.dp))
                    ReadPlaceholder(Modifier.fillMaxWidth(.5f).height(16.dp))
                    ReadPlaceholder(Modifier.fillMaxWidth().height(36.dp))
                }
            }
        }
    }
}

@Composable
private fun HomeCoverPlaceholders(columns: Int) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        repeat(columns) { Surface(Modifier.weight(1f).aspectRatio(.7f).readingCardShadow(ReadingSurfaceStyle.coverShape), shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.surfaceVariant) {} }
    }
}

@Composable
private fun HomeFeaturedPager(session: WorldSession, model: MixedDiscoveryModel, cards: List<MangaCard>, open: (String, String) -> Unit) {
    val pager = rememberPagerState { cards.size }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        HorizontalPager(pager, pageSpacing = 8.dp, beyondViewportPageCount = 0,
            verticalAlignment = Alignment.Top,
            key = { cards[it].key.let { key -> "${key.sourceId}:${key.mangaId}" } }, modifier = Modifier.fillMaxWidth()) { page ->
            HomeFeaturedBook(session, model, cards[page], page == pager.currentPage, open)
        }
        if (cards.size > 1) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(5.dp, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically) {
            repeat(cards.size) { page ->
                Box(Modifier.size(width = if (page == pager.currentPage) 20.dp else 5.dp, height = 5.dp)
                    .background(if (page == pager.currentPage) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                        RoundedCornerShape(50)))
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun HomeFeaturedBook(session: WorldSession, model: MixedDiscoveryModel, card: MangaCard, visible: Boolean, open: (String, String) -> Unit) {
    val identity by session.identity.collectAsStateWithLifecycle()
    val visibility by session.state.collectAsStateWithLifecycle()
    val metadata = model.featured
    val revision = metadata.revision
    val lifecycle by androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle.currentStateFlow.collectAsState()
    LaunchedEffect(identity, card.key, visible, visibility.foreground, visibility.visibilityEpoch, revision, lifecycle) {
        if (visible && visibility.foreground && lifecycle == androidx.lifecycle.Lifecycle.State.RESUMED) metadata.load(card)
    }
    val loaded = metadata.books[card.key]
    val information = loaded?.info
    val error = metadata.errors[card.key]
    val cardHeight = featuredCardHeight()
    Surface(onClick = { open(card.key.sourceId, card.key.mangaId) },
        modifier = Modifier.readingCardShadow(), shape = ReadingSurfaceStyle.cardShape,
        color = ReadingSurfaceStyle.card, border = ReadingSurfaceStyle.border) {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            // Bound the preview, even when cached metadata arrives later. Full text lives in detail.
            val contentHeight = cardHeight - 16.dp
            val coverWidth = featuredCoverWidth(maxWidth, cardHeight)
            val informationWidth = (maxWidth - coverWidth - 24.dp).coerceAtLeast(1.dp)
            Row(Modifier.fillMaxWidth().height(cardHeight), horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.width(coverWidth).fillMaxHeight()) {
                    OnlineCover(model.library, card.key.sourceId, card.key.mangaId, fillWidth = true,
                        integrated = true)
                    if (error != null) TextButton({ metadata.retry(card.key) }, Modifier.align(Alignment.BottomCenter)
                        .fillMaxWidth().background(MaterialTheme.colorScheme.surface.copy(alpha = .94f)),
                        contentPadding = PaddingValues(horizontal = 2.dp, vertical = 4.dp)) {
                        Text("$error，重试", style = MaterialTheme.typography.labelSmall, maxLines = 2)
                    }
                }
                Column(Modifier.weight(1f).fillMaxHeight().padding(top = 8.dp, bottom = 8.dp, end = 12.dp)
                    .verticalScroll(rememberScrollState()).heightIn(min = contentHeight),
                    verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    val singleTitle = HomeFeaturedTitle(card.title, informationWidth)
                    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        val author = information?.author?.takeIf(String::isNotBlank) ?: card.author
                        if (author.isNotBlank()) Text(author, style = MaterialTheme.typography.bodySmall,
                            color = ReadingSurfaceStyle.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        information?.tags?.takeIf { it.isNotEmpty() }?.let { tags -> ReadingTagBadges(tags) }
                        val chapterLabel = listOfNotNull(loaded?.chapters?.let { "共 $it 话" },
                            information?.status?.takeIf(String::isNotBlank)).joinToString(" · ")
                        if (chapterLabel.isNotBlank()) Text(chapterLabel, style = MaterialTheme.typography.bodySmall,
                            color = ReadingSurfaceStyle.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    information?.summary?.replace(Regex("\\s+"), " ")?.trim()?.takeIf(String::isNotBlank)?.let { summary ->
                        Text(summary, style = MaterialTheme.typography.bodySmall,
                            maxLines = if (singleTitle) 3 else 2,
                            overflow = TextOverflow.Ellipsis, color = ReadingSurfaceStyle.muted)
                    }
                }
            }
        }
    }
}

/** Measure wrapping at the same width as Text; never clip the second line with a fixed-height box. */
@Composable
private fun HomeFeaturedTitle(title: String, availableWidth: Dp): Boolean {
    val density = LocalDensity.current
    val measurer = rememberTextMeasurer()
    val text = remember(title) { title.replace(Regex("\\s+"), " ").trim() }
    val baseStyle = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold)
    val width = with(density) { availableWidth.roundToPx() }
    val singleSize = listOf(20, 19, 18, 17, 16).firstOrNull { size ->
        val measured = measurer.measure(text, style = baseStyle.copy(fontSize = size.sp, lineHeight = 28.sp),
            constraints = Constraints(maxWidth = width), softWrap = true, maxLines = 2,
            overflow = TextOverflow.Clip)
        measured.lineCount == 1 && !measured.hasVisualOverflow
    }
    val single = singleSize != null
    Text(text, Modifier.fillMaxWidth(),
        style = baseStyle.copy(fontSize = (singleSize ?: 16).sp, lineHeight = if (single) 28.sp else 22.sp),
        softWrap = true, minLines = if (single) 1 else 2, maxLines = if (single) 1 else 2,
        overflow = TextOverflow.Ellipsis)
    return single
}

/** Defer list replacement until scrolling settles; live item keys keep the visible anchor. */
@Composable
private fun stableHomeCards(owner: Any?, incoming: List<MangaCard>, scrolling: Boolean): List<MangaCard> {
    var shown by remember(owner) { mutableStateOf(incoming) }
    LaunchedEffect(owner, incoming, scrolling) { if (!scrolling) shown = incoming }
    return if (scrolling) shown else incoming
}
