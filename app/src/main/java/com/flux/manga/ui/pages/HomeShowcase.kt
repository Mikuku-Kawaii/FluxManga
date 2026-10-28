package com.flux.manga.ui.pages

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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.flux.manga.data.*
import com.flux.manga.session.WorldSession
import com.flux.manga.ui.navigation.DiscoverySection
import kotlinx.coroutines.CancellationException

@Composable
internal fun DiscoveryHomeShowcase(session: WorldSession, configuration: SourceDiscoveryModel, state: DiscoveryState,
    bottom: Dp, search: () -> Unit, sources: () -> Unit, settings: () -> Unit, switchError: String?,
    section: (DiscoverySection) -> Unit, open: (String, String) -> Unit, reportScroll: (LazyListState) -> Unit) {
    val recommendations = mixedModel(session, null, DiscoverySection.RECOMMENDATIONS)
    val recommendationState by recommendations.feed.state.collectAsStateWithLifecycle()
    val recommended = mixedCards(recommendations.feed.sources, recommendationState.results)
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
                configuration.refresh()
                listOfNotNull(recommendations, updates).forEach { model -> model.feed.sources.forEach { model.feed.refresh(it) } }
            }, modifier = Modifier.fillMaxSize().testTag("discovery-home"),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 4.dp, bottom = bottom),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            item("heading") { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                DiscoveryHeading("home-more", settings)
                ReferenceSearchEntry("搜索书名或作者", search, Modifier.testTag("open-search"))
                switchError?.let { RequestFeedback(it) }
            } }
            item("featured") { Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                DiscoverySectionHeading("图源推荐", "open-recommendations-status", actions = {
                    CompactFeedAction(recommendations.feed, recommendationState, recommendations.resultFeeds, sources)
                }) { section(DiscoverySection.RECOMMENDATIONS) }
                HomeFeedState(recommendations, recommended, sources)
                if (featured.isNotEmpty()) HomeFeaturedPager(session, recommendations, featured, open)
            } }
            item("updates-heading") { Column(Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                DiscoverySectionHeading("最近更新", "open-source-updates-status", actions = {
                    if (updates != null && updateState != null) CompactFeedAction(updates.feed, updateState, updates.resultFeeds, sources)
                }) { section(DiscoverySection.UPDATES) }
                if (updates != null) HomeFeedState(updates, updated, sources)
                else HomeCoverPlaceholders(columns)
                if (state.status == DiscoveryStatus.FAILED) TextButton(configuration::refresh) { Text("重试图源配置") }
            } }
            if (updates != null) bookGridItems(updated.take(9), columns, { "${it.key.sourceId}:${it.key.mangaId}" }) { card ->
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    DiscoveryGridBook(updates.library, card, open)
                    HomeUpdateMetadata(updates, card)
                }
            }
            if (remaining.isNotEmpty()) {
                item("more-heading") { Box(Modifier.padding(top = 8.dp)) {
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
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        HorizontalPager(pager, pageSpacing = 12.dp, beyondViewportPageCount = 0,
            verticalAlignment = Alignment.Top,
            key = { cards[it].key.let { key -> "${key.sourceId}:${key.mangaId}" } }, modifier = Modifier.fillMaxWidth()) { page ->
            HomeFeaturedBook(session, model, cards[page], open)
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
private fun HomeFeaturedBook(session: WorldSession, model: MixedDiscoveryModel, card: MangaCard, open: (String, String) -> Unit) {
    val context = LocalContext.current
    val identity by session.identity.collectAsStateWithLifecycle()
    val visibility by session.state.collectAsStateWithLifecycle()
    var information by remember(identity, card.key) { mutableStateOf<OnlineBookInformation?>(null) }
    LaunchedEffect(identity, card.key, visibility.foreground, visibility.visibilityEpoch) {
        if (!visibility.foreground) { information = null; return@LaunchedEffect }
        // Optional, already-saved metadata only; the home hero never starts a detail/catalog download.
        try {
            val cached = OnlineBookInformationStore(WorldDataStore(context), session).read(card.key.sourceId, card.key.mangaId)
            if (session.accepts(identity)) information = cached
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { information = null }
    }
    val scale = LocalDensity.current.fontScale
    Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surface) {
        BoxWithConstraints(Modifier.fillMaxWidth().background(Brush.linearGradient(listOf(
            MaterialTheme.colorScheme.primary.copy(alpha = .08f), MaterialTheme.colorScheme.surface))).padding(16.dp)) {
            @Composable fun Info(modifier: Modifier) {
                Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(card.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold,
                        maxLines = 3, overflow = TextOverflow.Ellipsis)
                    val author = information?.author?.takeIf(String::isNotBlank) ?: card.author
                    if (author.isNotBlank()) Text(author, style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    information?.tags?.take(3)?.takeIf { it.isNotEmpty() }?.let { tags ->
                        Text(tags.joinToString(" / "), style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                    information?.summary?.takeIf(String::isNotBlank)?.let {
                        Text(it, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    TextButton({ open(card.key.sourceId, card.key.mangaId) }) { Text("查看作品") }
                }
            }
            if (maxWidth < 290.dp || scale >= 1.5f) Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Box(Modifier.width(168.dp.coerceAtMost(maxWidth)).align(Alignment.CenterHorizontally)) {
                    OnlineCover(model.library, card.key.sourceId, card.key.mangaId, open = { open(card.key.sourceId, card.key.mangaId) }, fillWidth = true)
                }
                Info(Modifier.fillMaxWidth())
            } else Row(horizontalArrangement = Arrangement.spacedBy(18.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.width((maxWidth * .43f).coerceAtMost(180.dp))) {
                    OnlineCover(model.library, card.key.sourceId, card.key.mangaId, open = { open(card.key.sourceId, card.key.mangaId) }, fillWidth = true)
                }
                Info(Modifier.weight(1f))
            }
        }
    }
}
