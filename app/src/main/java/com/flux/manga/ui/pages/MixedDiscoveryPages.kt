package com.flux.manga.ui.pages

import android.app.Application
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.compose.*
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.flux.manga.core.*
import com.flux.manga.data.*
import com.flux.manga.session.WorldSession
import com.flux.manga.ui.navigation.*
import kotlinx.coroutines.*

internal class MixedDiscoveryModel(app: Application, private val session: WorldSession, komgaId: String?,
    section: DiscoverySection?, targets: List<TagTarget> = emptyList(), tagLabel: String = "") : ViewModel() {
    private val identity = session.identity.value
    private val komga = komgaId?.let { KomgaAccess(app, session, it) }
    private val privateCatalog = if (identity.world == PrototypeWorld.NSFW) PrivateCatalog(app, session) else null
    val library = com.flux.manga.data.OnlineLibrary(app, session)
    private val selection = SourceSelection(app, identity.world)
    private val tagQueries = if (section == null) mixedTagQueries(
        aggregateSearchSources(identity.world, komgaId).filter(selection::enabled), tagLabel, targets) else emptyList()
    private val sources = if (section == null) tagQueries.indices.map { "tag:$it" } else (if (privateCatalog != null) when (section) {
        DiscoverySection.RECOMMENDATIONS -> listOf(JM_SOURCE, PrivateCatalog.PICA)
        DiscoverySection.UPDATES -> listOf(JM_SOURCE, PrivateCatalog.PICA, EH_SOURCE, PrivateCatalog.EX)
        else -> emptyList()
    } else when (section) {
        DiscoverySection.RECOMMENDATIONS -> listOf(CopyMangaEndpoint.SOURCE_ID, BaoziEndpoint.SOURCE_ID)
        DiscoverySection.UPDATES -> listOfNotNull(komgaId, CopyMangaEndpoint.SOURCE_ID, BaoziEndpoint.SOURCE_ID)
        else -> emptyList()
    }).filter(selection::enabled)
    val resultFeeds = sources.mapIndexed { index, id -> ResultFeed(id, if (section == null) tagQueries[index].target.sourceId else id) }
    // A bounded last-result slot per feed; query/target identity is checked inside its envelope.
    private val snapshots = sources.associateWith { CatalogPageCache(app, session, "mixed:${section?.name ?: "tag"}:$it") }
    private val snapshotQuery = if (section != null) "" else tagQueries.joinToString("|", prefix = "mixed-tags-v2:") {
        val target = it.target
        "${target.sourceId.length}:${target.sourceId}${target.id.length}:${target.id}:${target.category}:${it.keyword}"
    }
    val feed = AggregateSearchController(viewModelScope, sources,
        { session.accepts(identity) },
        sourceIds = resultFeeds.associate { it.id to it.sourceId },
        restore = { source, _ -> snapshots.getValue(source).feed(snapshotQuery) },
        save = { source, _, state -> snapshots.getValue(source).saveFeed(snapshotQuery, state) },
        refreshRestored = section != null) { feedId, _, page ->
        val tagQuery = if (section == null) tagQueries[feedId.removePrefix("tag:").toInt()] else null
        val target = tagQuery?.target
        val source = target?.sourceId ?: feedId
        withContext(Dispatchers.IO) {
            if (privateCatalog != null) privateCatalog.load(source, target?.id.orEmpty(), page, when {
                target != null -> if (target.category) "category" else "search"
                section == DiscoverySection.RECOMMENDATIONS -> "recommendations"
                else -> "updates"
            })
            else if (section == null) {
                val id = requireNotNull(target).id
                when (source) {
                    CopyMangaEndpoint.SOURCE_ID -> session.copyMangaSources.client().let {
                        if (tagQuery?.keyword == true) it.search(id, page) else it.themed(id, page)
                    }
                    BaoziEndpoint.SOURCE_ID -> session.baoziSources.client(app).source.let {
                        if (tagQuery?.keyword == true) it.search(id, page)
                        else it.search("", page, tag = BaoziCategories.tags.getValue(id))
                    }
                    else -> requireNotNull(komga).client().let {
                        if (tagQuery?.keyword == true) it.search(id, page) else it.tagged(id, page)
                    }
                }
            } else when (source) {
                CopyMangaEndpoint.SOURCE_ID -> session.copyMangaSources.client().let {
                    if (section == DiscoverySection.RECOMMENDATIONS) it.recommendations(page) else it.updates(page)
                }
                BaoziEndpoint.SOURCE_ID -> session.baoziSources.client(app).source.let {
                    if (section == DiscoverySection.RECOMMENDATIONS) it.recommendations(page)
                    else it.search("", page, updates = true)
                }
                else -> requireNotNull(komga).client().updates(page)
            }
        }
    }
    fun start() { if (feed.state.value.results.isEmpty()) feed.submit("", allowEmpty = true) else feed.resume() }
}

@Composable
internal fun mixedModel(session: WorldSession, komgaId: String?, section: DiscoverySection?, targets: List<TagTarget> = emptyList(), tagLabel: String = ""): MixedDiscoveryModel {
    val app = LocalContext.current.applicationContext as Application
    val selection = SourceSelection(app, session.identity.value.world).fingerprint()
    val model: MixedDiscoveryModel = if (section != null) preloadedPages(session).mixed(komgaId, section)
    else viewModel(key = "mixed:$komgaId:$section:$targets:$tagLabel:$selection", factory = viewModelFactory {
        initializer { MixedDiscoveryModel(app, session, komgaId, section, targets, tagLabel) }
    })
    val lifecycle by LocalLifecycleOwner.current.lifecycle.currentStateFlow.collectAsState()
    DisposableEffect(model, lifecycle) {
        if (lifecycle == Lifecycle.State.RESUMED) model.start()
        onDispose { if (section == null) model.feed.leave() }
    }
    return model
}

@Composable
internal fun MixedFeedSummary(model: MixedDiscoveryModel, section: DiscoverySection, open: (String, String) -> Unit, sources: () -> Unit) {
    val state by model.feed.state.collectAsStateWithLifecycle()
    val cards = mixedCards(model.feed.sources, state.results).take(3)
    CompactFeedFeedback(model.feed, state, model.resultFeeds, sources, showAction = false)
    if (cards.isEmpty() && state.busy) ReadLayoutPlaceholder()
    if (cards.isEmpty() && !state.busy && state.results.values.any { it.error != null }) {
        ReadFailure(state.results.values.mapNotNull { it.error }.distinct().joinToString("\n")) {
            model.resultFeeds.filter { state.results[it.id]?.error != null }.forEach { model.feed.refresh(it.id) }
        }
    }
    if (cards.isEmpty() && !hasMixedFeedback(state)) Text(if (model.feed.sources.isEmpty()) "当前没有启用支持此栏目的图源" else "暂无作品", style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant)
    if (section == DiscoverySection.UPDATES) Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        cards.take(3).forEach { card -> key("${card.key.sourceId}:${card.key.mangaId}") {
            Row(Modifier.fillMaxWidth().clickable(role = androidx.compose.ui.semantics.Role.Button,
                onClickLabel = "查看《${card.title}》详情", onClick = { open(card.key.sourceId, card.key.mangaId) }),
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Box(Modifier.width(104.dp)) { OnlineCover(model.library, card.key.sourceId, card.key.mangaId,
                    fillWidth = true, coverRatio = 1.6f) }
                Column(Modifier.weight(1f).padding(vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(card.title, Modifier.fillMaxWidth(), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 2, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                    if (card.author.isNotBlank()) Text(card.author, style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        } }
    } else DiscoveryPreviewGrid(cards, { "${it.key.sourceId}:${it.key.mangaId}" }) { card ->
        DiscoveryGridBook(model.library, card, open)
    }
}

@Composable
internal fun MixedFeedPage(session: WorldSession, komgaId: String?, section: DiscoverySection, back: () -> Unit,
    sources: () -> Unit, open: (String, String) -> Unit, configurationError: Boolean = false, retryConfiguration: () -> Unit = {}) {
    val privateWorld = session.identity.value.world == PrototypeWorld.NSFW
    val help = if (privateWorld) "图源推荐合并 JM 首页推荐栏与已登录 PicACG 的首页推荐合集，展示图源当前返回的漫画预览；可下拉刷新，不以排行榜替代推荐。最近更新合并 JM / PicACG 最新作品与 EH / EX 最新画廊。各源账号、分页和错误保持独立。"
    else if (section == DiscoverySection.RECOMMENDATIONS)
        "推荐来自已开启的拷贝漫画与包子漫画，保留各站推荐顺序。Komga 暂不提供推荐内容。"
    else "更新来自已开启的拷贝漫画、包子漫画与已配置的 Komga，按来源交错展示并保留各站顺序。作品资料修改也可能列入更新。"
    MixedBookList(mixedModel(session, komgaId, section), section.title, back, sources, open, help, configurationError, retryConfiguration, grid = true)
}

@Composable
internal fun MixedTagBooksPage(session: WorldSession, route: UnifiedTagResults, back: () -> Unit,
    sources: () -> Unit, open: (String, String) -> Unit) {
    val configuration = discoveryModel(session)
    val state by configuration.state.collectAsStateWithLifecycle()
    val komgaId = if (session.identity.value.world == PrototypeWorld.NSFW) null else
        DiscoverySource.KOMGA.id(state) ?: route.targets.firstOrNull {
            !isCopySource(it.sourceId) && !isBaoziSource(it.sourceId)
        }?.sourceId
    MixedBookList(mixedModel(session, komgaId, null, route.targets, route.label), route.label, back, sources, open,
        "当前空间全部图源混合展示；已知标签使用原站条件，其他图源按标签名称搜索。各图源独立分页和重试。",
        configurationError = state.status == DiscoveryStatus.FAILED, retryConfiguration = configuration::refresh,
        grid = true, allSources = true)
}

@Composable
private fun MixedBookList(model: MixedDiscoveryModel, title: String, back: () -> Unit, sources: () -> Unit,
    open: (String, String) -> Unit, help: String, configurationError: Boolean = false, retryConfiguration: () -> Unit = {}, grid: Boolean = false, allSources: Boolean = false) {
    val state by model.feed.state.collectAsStateWithLifecycle()
    val feeds = model.resultFeeds
    var selectedSource by rememberSaveable(feeds) { mutableStateOf<String?>(null) }
    var showSources by rememberSaveable { mutableStateOf(false) }
    val activeSource = selectedSource?.takeIf { source -> !allSources && feeds.any { it.sourceId == source } }
    val visibleFeeds = resultScope(feeds, activeSource)
    val cards = mixedCards(visibleFeeds.map { it.id }, state.results)
    val scroll = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val selectSource: (String?) -> Unit = { selectedSource = it; scope.launch { scroll.scrollToItem(0) } }
    if (showSources) MixedSourceStatusDialog(model.feed, state, feeds, sources, { showSources = false })
    BookResultsLayout(enabled = grid) { columns ->
        ReferenceRefreshColumn(refreshing = state.busy, refresh = { visibleFeeds.filter { state.results[it.id]?.unsupported != true }.forEach { model.feed.refresh(it.id) }; if (configurationError) retryConfiguration() }, refreshEnabled = true, modifier = Modifier.fillMaxSize().testTag("mixed-book-list"), state = scroll,
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item("heading") { SettingsHeading(title, "", back, actions = {
                ResultActionsMenu(sources = sources, sourceStatus = { showSources = true },
                    help = if (allSources) help else "$help\n\n来源筛选只改变显示范围。加载更多仅作用于当前筛选范围；数量为已加载作品，不是站点总量。")
            }) }
            if (configurationError) item("configuration-error") { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                RequestFeedback("部分图源配置读取失败，其他作品仍可使用。", color = MaterialTheme.colorScheme.error)
                TextButton(onClick = feedbackAttempt(retryConfiguration)) { Text("重试读取配置") }
            } }
            item("filter") {
                if (allSources) MixedResultCount(cards.size) else ResultSourceFilter(feeds, activeSource, cards.size, selectSource)
            }
            if (cards.isEmpty() || hasMixedFeedback(state)) item("status") { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (cards.isNotEmpty()) CompactFeedFeedback(model.feed, state, feeds, sources)
                else {
                    MixedRequestControls(model.feed, state, feeds)
                    MixedResultEmpty(visibleFeeds, state, cards.size, activeSource != null, { selectSource(null) }, "暂无作品")
                }
            } }
            bookGridItems(cards, columns, { "${it.key.sourceId}:${it.key.mangaId}" }) {
                if (grid) DiscoveryGridBook(model.library, it, open) else DiscoveryFeedBook(model.library, it, open)
            }
            item("more") { MixedMoreButton(model.feed, state, visibleFeeds) }
        }
    }
}
