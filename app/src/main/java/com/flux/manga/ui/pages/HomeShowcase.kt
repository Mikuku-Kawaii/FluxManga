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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
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

@Composable
internal fun DiscoveryHomeShowcase(session: WorldSession, configuration: SourceDiscoveryModel, state: DiscoveryState,
    bottom: Dp, search: () -> Unit, sources: () -> Unit, settings: () -> Unit, switchError: String?,
    section: (DiscoverySection) -> Unit, open: (String, String) -> Unit, reportScroll: (LazyListState) -> Unit) {
    val recommendations = mixedModel(session, null, DiscoverySection.RECOMMENDATIONS)
    val recommendationState by recommendations.feed.state.collectAsStateWithLifecycle()
    val recommended = mixedCards(recommendations.feed.sources, recommendationState.results)
        .sortedBy { if (isCopySource(it.key.sourceId)) 0 else 1 }
    val updates = if (state.status != DiscoveryStatus.LOADING) mixedModel(session, state.sourceId, DiscoverySection.UPDATES) else null
    val updateState = updates?.feed?.state?.collectAsStateWithLifecycle()?.value
    val updated = if (updates != null && updateState != null) mixedCards(updates.feed.sources, updateState.results) else emptyList()
    val featured = recommended.take(5)
    val remaining = recommended.drop(featured.size).take(9)
    val scroll = rememberLazyListState()
    SideEffect { reportScroll(scroll) }
    BookResultsLayout { columns ->
        ReferenceRefreshColumn(state = scroll, refreshing = state.refreshing || recommendationState.busy || updateState?.busy == true,
            refresh = {
                recommendations.featured.refresh()
                configuration.refresh()
                listOfNotNull(recommendations, updates).forEach { model -> model.feed.sources.forEach { model.feed.refresh(it) } }
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
                HomeFeedState(recommendations, recommended, sources)
                if (featured.isNotEmpty()) HomeFeaturedPager(session, recommendations, featured, open)
            } }
            item("updates-heading") { Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                DiscoverySectionHeading("最近更新", "open-source-updates-status", actions = {
                    if (updates != null && updateState != null) CompactFeedAction(updates.feed, updateState, updates.resultFeeds, sources)
                }) { section(DiscoverySection.UPDATES) }
                if (updates != null) HomeFeedState(updates, updated, sources)
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
private fun HomeFeedState(model: MixedDiscoveryModel, cards: List<MangaCard>, sources: () -> Unit) {
    val state by model.feed.state.collectAsStateWithLifecycle()
    CompactFeedFeedback(model.feed, state, model.resultFeeds, sources, showAction = false)
    if (cards.isEmpty()) when {
        state.busy -> HomeCoverPlaceholders(3)
        state.results.values.any { it.error != null } -> ReadFailure(state.results.values.mapNotNull { it.error }.distinct().joinToString("\n")) {
            model.resultFeeds.filter { state.results[it.id]?.error != null }.forEach { model.feed.refresh(it.id) }
        }
        !hasMixedFeedback(state) -> Text(if (model.feed.sources.isEmpty()) "暂无可用图源" else "暂无作品", color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun HomeCoverPlaceholders(columns: Int) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        repeat(columns) { Surface(Modifier.weight(1f).aspectRatio(.7f), shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.surfaceVariant) {} }
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
        shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surface) {
        BoxWithConstraints(Modifier.fillMaxWidth().background(Brush.linearGradient(listOf(
            MaterialTheme.colorScheme.primary.copy(alpha = .08f), MaterialTheme.colorScheme.surface)))) {
            // Bound the preview, even when cached metadata arrives later. Full text lives in detail.
            val contentHeight = cardHeight - 16.dp
            val coverWidth = featuredCoverWidth(maxWidth, cardHeight)
            val informationWidth = (maxWidth - coverWidth - 20.dp).coerceAtLeast(1.dp)
            Row(Modifier.fillMaxWidth().height(contentHeight + 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.width(coverWidth)) {
                    OnlineCover(model.library, card.key.sourceId, card.key.mangaId, fillWidth = true,
                        coverRatio = coverWidth / (contentHeight + 16.dp))
                    if (error != null) TextButton({ metadata.retry(card.key) }, Modifier.align(Alignment.BottomCenter)
                        .fillMaxWidth().background(MaterialTheme.colorScheme.surface.copy(alpha = .94f)),
                        contentPadding = PaddingValues(horizontal = 2.dp, vertical = 4.dp)) {
                        Text("$error，重试", style = MaterialTheme.typography.labelSmall, maxLines = 2)
                    }
                }
                Column(Modifier.weight(1f).fillMaxHeight().padding(top = 8.dp, bottom = 8.dp, end = 12.dp)
                    .verticalScroll(rememberScrollState()).heightIn(min = contentHeight),
                    verticalArrangement = Arrangement.SpaceBetween) {
                    val singleTitle = HomeFeaturedTitle(card.title, informationWidth)
                    val author = information?.author?.takeIf(String::isNotBlank) ?: card.author
                    if (author.isNotBlank()) Text(author, style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    information?.tags?.takeIf { it.isNotEmpty() }?.let { tags ->
                        Text(tags.joinToString(" / "), style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    val chapterLabel = listOfNotNull(loaded?.chapters?.let { "共 $it 话" },
                        information?.status?.takeIf(String::isNotBlank)).joinToString(" · ")
                    if (chapterLabel.isNotBlank()) Text(chapterLabel, style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    information?.summary?.replace(Regex("\\s+"), " ")?.trim()?.takeIf(String::isNotBlank)?.let { summary ->
                        Text(summary, style = MaterialTheme.typography.bodySmall,
                            minLines = if (singleTitle) 3 else 2, maxLines = if (singleTitle) 3 else 2,
                            overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
