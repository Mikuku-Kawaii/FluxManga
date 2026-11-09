package com.flux.manga.ui.pages

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.pager.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
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

private val recommendationShadowInset = 6.dp
private val recommendationLineStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.None)

private data class RecommendationLayout(
    val title: TextStyle, val summary: TextStyle, val metadata: TextStyle,
    val textLine: Dp, val authorHeight: Dp, val badgeHeight: Dp, val height: Dp,
)

@Composable
private fun recommendationLayout(): RecommendationLayout {
    val typography = MaterialTheme.typography
    val inherited = LocalTextStyle.current
    val title = inherited.merge(typography.titleMedium).copy(fontWeight = FontWeight.SemiBold, lineHeight = 20.sp,
        lineHeightStyle = recommendationLineStyle)
    val meta = inherited.merge(typography.bodySmall)
    val summary = meta.copy(lineHeight = title.lineHeight, lineHeightStyle = recommendationLineStyle)
    val tag = inherited.merge(typography.labelSmall).copy(fontSize = 11.sp, lineHeight = 16.sp, fontWeight = FontWeight.Normal)
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    return remember(density, measurer, title, summary, meta, tag) {
        // Include the actual fallback font metrics and round each line up before allocating the slots.
        fun linePixels(style: TextStyle): Int {
            val single = measurer.measure("漫Ag", style = style).size.height
            val double = measurer.measure("漫Ag\n漫Ag", style = style).size.height
            return maxOf(single, double - single, with(density) { kotlin.math.ceil(style.lineHeight.toPx()).toInt() }) + 1
        }
        with(density) {
            val line = maxOf(linePixels(title), linePixels(summary))
            val author = linePixels(meta)
            val badge = linePixels(tag) + 2 * 2.dp.roundToPx()
            val paddingAndGaps = 2 * 8.dp.roundToPx() + 3 * 4.dp.roundToPx()
            val height = (4 * line + author + badge + paddingAndGaps).toDp()
            RecommendationLayout(title, summary, meta, line.toDp(), author.toDp(), badge.toDp(), maxOf(152.dp, height))
        }
    }
}

@Composable
private fun Modifier.recommendationCardFrame(layout: RecommendationLayout) =
    padding(horizontal = 4.dp, vertical = recommendationShadowInset).height(layout.height).readingCardShadow()

/** Both worlds keep the final recommendation-card geometry while restoring or fetching data. */
@Composable
private fun HomeFeaturedPlaceholder() {
    val layout = recommendationLayout()
    Surface(modifier = Modifier.recommendationCardFrame(layout).semantics(mergeDescendants = true) { contentDescription = "正在加载推荐" },
        shape = ReadingSurfaceStyle.cardShape, color = ReadingSurfaceStyle.card, border = ReadingSurfaceStyle.border) {
        FeaturedCardFrame(cover = {
            Spacer(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceVariant))
        }) { width -> RecommendationInfo(layout, width, "", "", emptyList(), "", placeholder = true) }
    }
}

@Composable
private fun HomeCoverPlaceholders(columns: Int) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        repeat(columns) { Surface(Modifier.weight(1f).aspectRatio(ReadingSurfaceStyle.bookCoverRatio).readingCardShadow(ReadingSurfaceStyle.coverShape), shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.surfaceVariant) {} }
    }
}

@Composable
private fun HomeFeaturedPager(session: WorldSession, model: MixedDiscoveryModel, cards: List<MangaCard>, open: (String, String) -> Unit) {
    val pager = rememberPagerState { cards.size }
    val layout = recommendationLayout()
    val pagerHeight = layout.height + recommendationShadowInset * 2
    Box(Modifier.fillMaxWidth().height(pagerHeight)) {
        HorizontalPager(pager, pageSpacing = 8.dp, beyondViewportPageCount = 0,
            verticalAlignment = Alignment.Top,
            key = { cards[it].key.let { key -> "${key.sourceId}:${key.mangaId}" } }, modifier = Modifier.matchParentSize()) { page ->
            HomeFeaturedBook(session, model, cards[page], page == pager.currentPage, layout, open)
        }
        // Anchor the dots below the pager without adding a row to the section height.
        if (cards.size > 1) Row(Modifier.align(Alignment.TopCenter).offset(y = pagerHeight + 6.dp), horizontalArrangement = Arrangement.spacedBy(5.dp),
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
private fun HomeFeaturedBook(session: WorldSession, model: MixedDiscoveryModel, card: MangaCard, visible: Boolean, layout: RecommendationLayout, open: (String, String) -> Unit) {
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
    Surface(onClick = { open(card.key.sourceId, card.key.mangaId) },
        modifier = Modifier.recommendationCardFrame(layout), shape = ReadingSurfaceStyle.cardShape,
        color = ReadingSurfaceStyle.card, border = ReadingSurfaceStyle.border) {
        FeaturedCardFrame(cover = {
            OnlineCover(model.library, card.key.sourceId, card.key.mangaId, fillWidth = true, integrated = true)
            val caption = listOfNotNull(information?.status?.trim()?.takeIf(String::isNotEmpty),
                loaded?.chapters?.let { "${it}话" }).joinToString(" · ")
            if (caption.isNotEmpty()) Text(caption, Modifier.align(Alignment.BottomStart).fillMaxWidth()
                .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = .75f))))
                .padding(start = 6.dp, end = 4.dp, top = 12.dp, bottom = 4.dp),
                color = Color.White, style = MaterialTheme.typography.labelSmall,
                maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (error != null) TextButton({ metadata.retry(card.key) }, Modifier.align(Alignment.TopCenter)
                .fillMaxWidth().background(MaterialTheme.colorScheme.surface.copy(alpha = .94f)),
                contentPadding = PaddingValues(horizontal = 2.dp, vertical = 4.dp)) {
                Text("$error，重试", style = MaterialTheme.typography.labelSmall, maxLines = 2)
            }
        }) { width ->
            RecommendationInfo(layout, width, card.title, information?.author?.takeIf(String::isNotBlank) ?: card.author,
                information?.tags ?: card.sourceTags.orEmpty(), information?.summary.orEmpty())
        }
    }
}

@Composable
private fun RecommendationInfo(layout: RecommendationLayout, width: Dp, title: String, author: String, tags: List<String>,
    summary: String, placeholder: Boolean = false) {
    val ink = ReadingSurfaceStyle.muted
    val density = LocalDensity.current
    val titleText = remember(title) { title.replace(Regex("\\s+"), " ").trim() }
    // Four text lines shared by the title and synopsis, at the same line height.
    val titleStyle = layout.title
    val summaryStyle = layout.summary
    val measurer = rememberTextMeasurer()
    val widthPx = with(density) { width.roundToPx() }
    val titleLines = if (placeholder) 2 else measurer.measure(titleText, style = titleStyle, maxLines = 2,
        overflow = TextOverflow.Ellipsis, constraints = Constraints(maxWidth = widthPx)).lineCount.coerceIn(1, 2)
    val summaryLines = 4 - titleLines
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        RecommendationSlot(layout.textLine * titleLines, placeholder, .88f) {
            Text(titleText, Modifier.fillMaxWidth(), style = titleStyle, maxLines = titleLines, overflow = TextOverflow.Ellipsis)
        }
        RecommendationSlot(layout.authorHeight, placeholder, .55f) {
            Text(author, style = layout.metadata, color = ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        RecommendationSlot(layout.badgeHeight, placeholder, .8f) {
            ReadingTagBadges(tags, width)
        }
        RecommendationSlot(layout.textLine * summaryLines, placeholder, .96f) {
            Text(summary.replace(Regex("\\s+"), " ").trim(), Modifier.fillMaxWidth(), style = summaryStyle, color = ink, maxLines = summaryLines,
                overflow = TextOverflow.Ellipsis)
        }
    }
}

/** Loading bars follow the same text slots as the final content. */
@Composable
private fun RecommendationSlot(height: Dp, placeholder: Boolean, fraction: Float, content: @Composable () -> Unit) {
    Box(Modifier.fillMaxWidth().height(height), contentAlignment = Alignment.CenterStart) {
        content()
        if (placeholder) Row(Modifier.matchParentSize()) {
            ReadPlaceholder(Modifier.fillMaxWidth(fraction).fillMaxHeight())
        }
    }
}

/** Defer list replacement until scrolling settles; live item keys keep the visible anchor. */
@Composable
private fun stableHomeCards(owner: Any?, incoming: List<MangaCard>, scrolling: Boolean): List<MangaCard> {
    var shown by remember(owner) { mutableStateOf(incoming) }
    LaunchedEffect(owner, incoming, scrolling) { if (!scrolling) shown = incoming }
    return if (scrolling) shown else incoming
}
